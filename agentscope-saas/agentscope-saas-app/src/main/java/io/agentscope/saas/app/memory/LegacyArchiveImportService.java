/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.SessionRunFenceService;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Mapping;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Owner;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.SessionFence;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in administrative import. Object verification never runs inside a database transaction. */
@Service
public class LegacyArchiveImportService {
    public static final String LABEL = "legacy";
    private final LegacyArchiveImportRepository jobs;
    private final RuntimeMessageRepository messages;
    private final ChatSessionRepository sessions;
    private final RunOrchestrationRepository runs;
    private final FileVersionRepository versions;
    private final FileCatalogService files;
    private final PgSessionArchiveStore archive;
    private final WorkspaceTransferResources transfers;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private final LegacyJsonlReader reader;

    public LegacyArchiveImportService(
            LegacyArchiveImportRepository jobs,
            RuntimeMessageRepository messages,
            ChatSessionRepository sessions,
            RunOrchestrationRepository runs,
            FileVersionRepository versions,
            FileCatalogService files,
            PgSessionArchiveStore archive,
            WorkspaceTransferResources transfers,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        this.jobs = jobs;
        this.messages = messages;
        this.sessions = sessions;
        this.runs = runs;
        this.versions = versions;
        this.files = files;
        this.archive = archive;
        this.transfers = transfers;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(10);
        reader = new LegacyJsonlReader(json);
        var cfg = config();
        if (cfg.getMaxSourceBytes() < 1
                || cfg.getMaxSourceBytes() > 1073741824L
                || cfg.getMaxLineBytes() < 1
                || cfg.getMaxLineBytes() > 1048576
                || cfg.getMaxBatchBytes() < cfg.getMaxLineBytes()
                || cfg.getMaxBatchBytes() > 16777216
                || cfg.getMaxBatchLines() < 1
                || cfg.getMaxBatchLines() > 500)
            throw new IllegalArgumentException("Invalid legacy import limits");
    }

    private SaasProperties.RuntimeArchive.LegacyImport config() {
        return properties.getRuntimeArchive().getLegacyImport();
    }

    public Job create(Owner owner, UUID sourceVersionId) {
        requireEnabled();
        return owned(
                owner,
                () ->
                        transactions.execute(
                                tx -> {
                                    requireSession(owner);
                                    long generation = lockGeneration(owner);
                                    var version =
                                            versions.findByIdAndOrgIdAndUserId(
                                                            sourceVersionId,
                                                            owner.orgId(),
                                                            owner.userId())
                                                    .filter(
                                                            v ->
                                                                    owner.agentId()
                                                                            .equals(v.getAgentId()))
                                                    .filter(
                                                            v ->
                                                                    v.getSessionId() == null
                                                                            || owner.sessionId()
                                                                                    .equals(
                                                                                            v
                                                                                                    .getSessionId()))
                                                    .orElseThrow(
                                                            LegacyArchiveImportService::notFound);
                                    if (version.getSizeBytes() == null
                                            || version.getSizeBytes() < 0
                                            || version.getSizeBytes() > config().getMaxSourceBytes()
                                            || version.getSha256() == null
                                            || !version.getSha256().matches("[a-f0-9]{64}"))
                                        throw new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Invalid legacy source version");
                                    var now = OffsetDateTime.now();
                                    jobs.create(
                                            new Job(
                                                    UUID.randomUUID(),
                                                    owner.orgId(),
                                                    owner.userId(),
                                                    owner.agentId(),
                                                    owner.sessionId(),
                                                    generation,
                                                    1,
                                                    sourceVersionId,
                                                    version.getSha256(),
                                                    version.getSizeBytes(),
                                                    0,
                                                    0,
                                                    0,
                                                    0,
                                                    "0".repeat(64),
                                                    "READY",
                                                    now,
                                                    now));
                                    return jobs.findBySource(owner, sourceVersionId)
                                            .orElseThrow(LegacyArchiveImportService::notFound);
                                }));
    }

    public Job get(Owner owner, UUID id) {
        requireEnabled();
        return owned(
                owner,
                () -> {
                    requireSession(owner);
                    return jobs.find(owner, id).orElseThrow(LegacyArchiveImportService::notFound);
                });
    }

    public List<Mapping> mappings(Owner owner, UUID id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 500)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid legacy mapping window");
        get(owner, id);
        return owned(owner, () -> jobs.mappings(owner, id, after, limit));
    }

    public Job advance(Owner owner, UUID id, long expectedOffset) {
        return advance(owner, id, expectedOffset, 1);
    }

    public Job advance(Owner owner, UUID id, long expectedOffset, int maxBatches) {
        requireEnabled();
        if (maxBatches < 1 || maxBatches > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid legacy batch count");
        return owned(owner, () -> advanceOwned(owner, id, expectedOffset, maxBatches));
    }

    private Job advanceOwned(Owner owner, UUID id, long expectedOffset, int maxBatches) {
        Job job = get(owner, id);
        if (job.formatVersion() != 1)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Legacy format version unsupported");
        if (expectedOffset < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid legacy cursor");
        if (expectedOffset != job.byteOffset()) throw conflict();
        if ("COMPLETE".equals(job.status())) return job;
        var tenant =
                new TenantContext(
                        owner.orgId().toString(),
                        owner.userId().toString(),
                        "member",
                        "standard",
                        0,
                        0);
        var source =
                files.describeVersion(tenant, job.sourceVersionId())
                        .orElseThrow(LegacyArchiveImportService::notFound);
        if (source.sizeBytes() != job.sourceBytes()
                || !Objects.equals(source.sha256(), job.sourceSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legacy source version changed");
        try (var spool = transfers.spool("legacy-archive-", Math.max(1, job.sourceBytes()))) {
            // Verify the entire immutable evidence before committing any batch, with a disk/IO
            // budget.
            try (var permit = transfers.acquire();
                    var output = spool.output()) {
                new WorkspaceFile(
                                source.logicalPath(),
                                source.sizeBytes(),
                                source.sha256(),
                                source.source())
                        .transferTo(output, transfers.bufferBytes());
            }
            Job progress = job;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            for (int n = 0; n < maxBatches; n++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var batch =
                        reader.read(
                                spool.path(),
                                progress,
                                config().getMaxBatchLines(),
                                config().getMaxLineBytes(),
                                config().getMaxBatchBytes());
                progress = commitBatch(owner, id, progress, batch, tenant);
                if ("COMPLETE".equals(progress.status()) || System.nanoTime() >= deadline) break;
            }
            return progress;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Legacy import interrupted");
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Legacy source is invalid or unavailable");
        } catch (WorkspaceTransferResources.CapacityException busy) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Legacy transfer capacity unavailable");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Legacy source transfer is unavailable");
        }
    }

    private Job commitBatch(
            Owner owner, UUID id, Job job, LegacyJsonlReader.Batch batch, TenantContext tenant) {
        var context =
                RuntimeContext.builder()
                        .userId(owner.userId().toString())
                        .sessionId(owner.sessionId().toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, owner.agentId().toString())
                        .put(
                                SessionRunFenceService.Binding.class,
                                new SessionRunFenceService.Binding(
                                        null,
                                        owner.orgId(),
                                        owner.userId(),
                                        owner.agentId(),
                                        new SessionFence(
                                                owner.sessionId(), job.sessionGeneration())))
                        .build();
        var batchMessages =
                batch.lines().stream()
                        .map(LegacyJsonlReader.Line::message)
                        .filter(Objects::nonNull)
                        .toList();
        // If progress commit crashes, the same stable IDs safely reuse these source commits.
        archive.append(context, LABEL, job.archiveKey(), batchMessages);
        return transactions.execute(
                tx -> {
                    requireSession(owner);
                    if (lockGeneration(owner) != job.sessionGeneration())
                        throw new SessionExecutionRevokedException();
                    Job locked =
                            jobs.lock(owner, id).orElseThrow(LegacyArchiveImportService::notFound);
                    if (locked.byteOffset() != job.byteOffset()
                            || locked.lineNo() != job.lineNo()
                            || !"READY".equals(locked.status())) {
                        if (locked.byteOffset() >= batch.offset()) return locked;
                        throw conflict();
                    }
                    var scope =
                            new Scope(
                                    owner.orgId(),
                                    owner.userId(),
                                    owner.agentId(),
                                    owner.sessionId(),
                                    LABEL,
                                    job.archiveKey());
                    var stream = messages.findStream(scope).orElseThrow();
                    var identities =
                            messages
                                    .findIdentities(
                                            stream.id(),
                                            scope,
                                            batchMessages.stream()
                                                    .map(m -> m.getId())
                                                    .distinct()
                                                    .toList())
                                    .stream()
                                    .collect(
                                            Collectors.toMap(
                                                    RuntimeMessageRepository.Identity::messageId,
                                                    i -> i));
                    long imported = 0;
                    for (var line : batch.lines()) {
                        String messageId = line.message() == null ? null : line.message().getId();
                        var identity = messageId == null ? null : identities.get(messageId);
                        String hash =
                                line.message() == null
                                        ? null
                                        : SessionArchiveStore.digest(line.message());
                        if (messageId != null
                                && (identity == null || !identity.contentHash().equals(hash)))
                            throw new IllegalStateException("LEGACY_IMPORT_SOURCE_COMMIT_MISSING");
                        jobs.insert(
                                new Mapping(
                                        job.id(),
                                        owner.orgId(),
                                        owner.userId(),
                                        line.number(),
                                        line.start(),
                                        line.end(),
                                        line.hash(),
                                        line.sourceId(),
                                        line.parentId(),
                                        line.toolCallId(),
                                        line.type(),
                                        line.synthetic(),
                                        messageId,
                                        identity == null ? null : identity.seq(),
                                        hash));
                        if (messageId != null) imported++;
                    }
                    if (batch.complete()) {
                        var proof = jobs.proof(owner, id);
                        if (proof.entries() != job.lineNo() + batch.lines().size()
                                || proof.converted() != job.importedMessages() + imported
                                || proof.lastByteEnd() != job.sourceBytes())
                            throw new IllegalStateException("LEGACY_IMPORT_COUNT_MISMATCH");
                    }
                    if (!jobs.advance(
                            job,
                            batch.offset(),
                            job.lineNo() + batch.lines().size(),
                            job.importedMessages() + imported,
                            job.skippedEntries() + batch.lines().size() - imported,
                            batch.digestChain(),
                            batch.complete())) throw conflict();
                    return jobs.find(owner, id).orElseThrow(LegacyArchiveImportService::notFound);
                });
    }

    private void requireSession(Owner owner) {
        sessions.findByIdAndOrgIdAndUserIdAndAgentId(
                        owner.sessionId(), owner.orgId(), owner.userId(), owner.agentId())
                .orElseThrow(LegacyArchiveImportService::notFound);
    }

    private void requireEnabled() {

        if (!config().isEnabled() || !properties.getRuntimeArchive().isEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Legacy import is disabled");
    }

    private long lockGeneration(Owner owner) {
        return runs.lockSessionGeneration(
                        owner.sessionId(), owner.orgId(), owner.userId(), owner.agentId())
                .orElseThrow(LegacyArchiveImportService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Legacy archive source or session not found");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Legacy cursor changed; reload progress");
    }

    private static <T> T owned(Owner owner, Supplier<T> action) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(owner.orgId().toString());
        try {
            return action.get();
        } finally {
            TenantContextHolder.setOrgId(prior);
        }
    }
}
