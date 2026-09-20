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
import io.agentscope.saas.domain.orchestration.ContextCheckpoint;
import io.agentscope.saas.domain.orchestration.ContextCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Persists monotonic context checkpoints under the same execution lease used by tools. */
@Service
public class DurableContextCheckpointService {
    private final ContextCheckpointRepository repository;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

    public DurableContextCheckpointService(
            ContextCheckpointRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public ContextCheckpointStore.StoredCheckpoint save(
            UUID orgId, UUID runId, ContextCheckpointStore.Draft draft) {
        ContextCheckpointStore.StoredCheckpoint saved =
                transactions.execute(status -> saveInTransaction(orgId, runId, draft));
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
        return latest(orgId, runId, agentRunId).map(this::toRecoveryCheckpoint);
    }

    private ContextCheckpointStore.StoredCheckpoint saveInTransaction(
            UUID orgId, UUID runId, ContextCheckpointStore.Draft draft) {
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
                        json(draft.retainedTail()),
                        draft.retainedFactsVersion(),
                        json(draft.pendingOperationIds()),
                        draft.workspaceVersion(),
                        now);
        if (repository.insert(checkpoint) != 1) {
            throw new IllegalStateException("Failed to insert context checkpoint");
        }
        return new ContextCheckpointStore.StoredCheckpoint(revision, draft.historyHash());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize context checkpoint", error);
        }
    }

    private ContextCheckpointStore.RecoveryCheckpoint toRecoveryCheckpoint(
            ContextCheckpoint checkpoint) {
        try {
            List<Msg> retainedTail =
                    objectMapper.readValue(
                            checkpoint.retainedTailJson(), new TypeReference<List<Msg>>() {});
            List<String> pendingOperations =
                    objectMapper.readValue(
                            checkpoint.pendingOperationsJson(),
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

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }
}
