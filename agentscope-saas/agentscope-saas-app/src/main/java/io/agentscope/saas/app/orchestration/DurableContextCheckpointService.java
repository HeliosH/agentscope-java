/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.orchestration.ContextCheckpoint;
import io.agentscope.saas.domain.orchestration.ContextCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Persists monotonic context checkpoints under the same execution lease used by tools. */
@Service
public class DurableContextCheckpointService {
    private final ContextCheckpointRepository repository;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;
    private final CheckpointTailCodec codec;
    private final SaasProperties properties;

    public DurableContextCheckpointService(
            ContextCheckpointRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            ObjectMapper objectMapper) {
        this(repository, transactions, objectMapper, null, new SaasProperties());
    }

    @Autowired
    public DurableContextCheckpointService(
            ContextCheckpointRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            ObjectMapper objectMapper,
            CheckpointTailCodec codec,
            SaasProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.codec = codec;
        this.properties = properties;
    }

    public ContextCheckpointStore.StoredCheckpoint save(
            UUID orgId, UUID runId, ContextCheckpointStore.Draft draft) {
        return save(orgId, runId, draft, null);
    }

    public boolean usesLightweightCheckpoints() {
        return codec != null && codec.enabled();
    }

    public ContextCheckpointStore.StoredCheckpoint save(
            UUID orgId,
            UUID runId,
            ContextCheckpointStore.Draft draft,
            CheckpointTailCodec.Binding binding) {
        transactions.executeWithoutResult(status -> lockAttempt(orgId, runId, draft));
        String pending = json(draft.pendingOperationIds());
        long budget =
                properties.getRuntimeArchive().getCheckpointMaxJsonBytes()
                        - CheckpointTailCodec.bytes(json(draft.summary()))
                        - CheckpointTailCodec.bytes(pending);
        if (budget < 1) throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
        Scope authorized = binding == null ? null : codec.authorize(orgId, runId, binding);
        CheckpointTailCodec.Prepared prepared =
                codec != null && codec.enabled() && binding != null
                        ? codec.prepare(orgId, runId, binding, draft.retainedTail(), budget)
                        : null;
        if (prepared == null) validateLegacyTail(draft.retainedTail(), budget);
        String tail = prepared == null ? json(draft.retainedTail()) : prepared.json();
        if (CheckpointTailCodec.bytes(tail) > budget)
            throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
        ContextCheckpointStore.StoredCheckpoint saved =
                transactions.execute(
                        status ->
                                saveInTransaction(
                                        orgId, runId, draft, tail, pending, prepared, authorized));
        if (saved == null) throw new IllegalStateException("Checkpoint transaction returned null");
        return saved;
    }

    public Optional<ContextCheckpoint> latest(UUID orgId, UUID runId, UUID agentRunId) {
        Optional<ContextCheckpoint> checkpoint =
                transactions.execute(status -> repository.findLatest(orgId, runId, agentRunId));
        return checkpoint == null ? Optional.empty() : checkpoint;
    }

    public Optional<ContextCheckpointStore.RecoveryCheckpoint> latestRecovery(
            UUID orgId, UUID runId, UUID agentRunId) {
        return latestRecovery(orgId, runId, agentRunId, null);
    }

    public Optional<ContextCheckpointStore.RecoveryCheckpoint> latestRecovery(
            UUID orgId, UUID runId, UUID agentRunId, CheckpointTailCodec.Binding binding) {
        return latest(orgId, runId, agentRunId)
                .map(checkpoint -> toRecoveryCheckpoint(checkpoint, binding));
    }

    private ContextCheckpointStore.StoredCheckpoint saveInTransaction(
            UUID orgId,
            UUID runId,
            ContextCheckpointStore.Draft draft,
            String tail,
            String pending,
            CheckpointTailCodec.Prepared prepared,
            Scope authorized) {
        if (authorized != null && !repository.lockArchiveScope(orgId, runId, authorized))
            throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
        lockAttempt(orgId, runId, draft);
        UUID taskId = uuid(draft.identity().taskId(), "taskId");
        UUID agentRunId = uuid(draft.identity().agentRunId(), "agentRunId");
        UUID attemptId = uuid(draft.identity().attemptId(), "attemptId");
        OffsetDateTime now = OffsetDateTime.now();
        if (prepared != null) {
            for (UUID body : prepared.bodyIds())
                if (repository.attachBody(
                                prepared.envelope().scope(),
                                body,
                                now.plusSeconds(
                                        properties.getRuntimeArchive().getBodyGraceSeconds()))
                        != 1) throw new IllegalStateException("CHECKPOINT_BODY_PUBLICATION_FENCED");
        }
        long revision = Math.addExact(repository.latestRevision(orgId, runId, agentRunId), 1L);
        ContextCheckpointRepository.NewCheckpoint checkpoint =
                new ContextCheckpointRepository.NewCheckpoint(
                        UUID.randomUUID(),
                        orgId,
                        runId,
                        taskId,
                        agentRunId,
                        attemptId,
                        revision,
                        draft.stepId(),
                        draft.historyHash(),
                        draft.summary(),
                        tail,
                        draft.retainedFactsVersion(),
                        pending,
                        draft.workspaceVersion(),
                        now);
        if (repository.insert(checkpoint) != 1)
            throw new IllegalStateException("Failed to insert context checkpoint");
        if (prepared != null) {
            for (UUID body : prepared.bodyIds())
                if (repository.insertBodyReference(checkpoint.id(), orgId, body) != 1)
                    throw new IllegalStateException("Failed to pin checkpoint body");
        }
        return new ContextCheckpointStore.StoredCheckpoint(revision, draft.historyHash());
    }

    private void lockAttempt(UUID orgId, UUID runId, ContextCheckpointStore.Draft draft) {
        UUID identityRunId = uuid(draft.identity().runId(), "runId");
        if (!runId.equals(identityRunId)) {
            throw new IllegalStateException("Checkpoint is not bound to this durable run");
        }
        UUID taskId = uuid(draft.identity().taskId(), "taskId");
        UUID agentRunId = uuid(draft.identity().agentRunId(), "agentRunId");
        UUID attemptId = uuid(draft.identity().attemptId(), "attemptId");
        String leaseOwner = draft.executionLease() == null ? null : draft.executionLease().owner();
        OffsetDateTime now = OffsetDateTime.now();
        if (!repository.lockActiveScope(
                orgId, runId, taskId, agentRunId, attemptId, leaseOwner, now)) {
            throw new ToolLeaseLostException(
                    "Execution lease is no longer valid for context checkpoint " + draft.stepId());
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize context checkpoint", error);
        }
    }

    private ContextCheckpointStore.RecoveryCheckpoint toRecoveryCheckpoint(
            ContextCheckpoint checkpoint, CheckpointTailCodec.Binding binding) {
        try {
            if (binding != null) codec.authorize(checkpoint.orgId(), checkpoint.runId(), binding);
            if (CheckpointTailCodec.bytes(checkpoint.retainedTailJson())
                    > properties.getRuntimeArchive().getMaxWindowBytes())
                throw new IllegalArgumentException("CHECKPOINT_TAIL_BYTE_LIMIT");
            String tailJson = unwrappedJson(checkpoint.retainedTailJson());
            List<Msg> retainedTail =
                    tailJson.stripLeading().startsWith("[")
                            ? objectMapper.readValue(tailJson, new TypeReference<List<Msg>>() {})
                            : codec.restore(
                                    checkpoint.orgId(), checkpoint.runId(), binding, tailJson);
            List<String> pendingOperations =
                    objectMapper.readValue(
                            unwrappedJson(checkpoint.pendingOperationsJson()),
                            new TypeReference<List<String>>() {});
            return new ContextCheckpointStore.RecoveryCheckpoint(
                    checkpoint.historyRevision(),
                    checkpoint.historyHash(),
                    checkpoint.summary(),
                    retainedTail,
                    pendingOperations,
                    checkpoint.workspaceVersion());
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to deserialize context checkpoint " + checkpoint.id(), error);
        }
    }

    private String unwrappedJson(String json) throws java.io.IOException {
        // Legacy H2 CAST(string AS JSON) encoded the document as a single JSON string.
        var node = objectMapper.readTree(json);
        return node.isTextual() ? node.textValue() : json;
    }

    private void validateLegacyTail(List<Msg> tail, long budget) {
        if (tail.size() > 500) throw new IllegalArgumentException("CHECKPOINT_TAIL_LIMIT");
        long total = 2;
        for (Msg message : tail) {
            total =
                    Math.addExact(
                            total,
                            CheckpointTailCodec.bytes(JsonUtils.getJsonCodec().toJson(message))
                                    + 1);
            if (total > Math.min(budget, properties.getRuntimeArchive().getMaxWindowBytes()))
                throw new IllegalArgumentException("CHECKPOINT_JSON_BYTE_LIMIT");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }
}
