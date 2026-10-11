/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.RuntimeBodyService;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService;
import io.agentscope.saas.app.orchestration.WorkspaceManifestVersion;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Authority;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Item;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Job;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only materialized, completely scanned outputs are eligible. No model/tools/provider dependency. */
@Service
public class WorkspaceArchiveService {
    private final WorkspaceArchiveRepository archives;
    private final WorkspaceManifestRepository manifests;
    private final WorkspaceManifestService manifestService;
    private final FilePublicationRepository publications;
    private final FileCatalogService catalog;
    private final RuntimeBodyService bodies;
    private final ChatPersistenceService chats;
    private final DurableTaskLeaseService leases;
    private final io.agentscope.saas.domain.sandbox.SandboxLeaseRepository sandboxLeases;
    private final RunOrchestrationRepository runs;
    private final ObjectMapper mapper;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private final WorkspaceSourceService sources;

    public WorkspaceArchiveService(
            WorkspaceArchiveRepository archives,
            WorkspaceManifestRepository manifests,
            WorkspaceManifestService manifestService,
            FilePublicationRepository publications,
            FileCatalogService catalog,
            RuntimeBodyService bodies,
            ChatPersistenceService chats,
            DurableTaskLeaseService leases,
            io.agentscope.saas.domain.sandbox.SandboxLeaseRepository sandboxLeases,
            RunOrchestrationRepository runs,
            ObjectMapper mapper,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(
                archives,
                manifests,
                manifestService,
                publications,
                catalog,
                bodies,
                chats,
                leases,
                sandboxLeases,
                runs,
                mapper,
                properties,
                manager,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceArchiveService(
            WorkspaceArchiveRepository archives,
            WorkspaceManifestRepository manifests,
            WorkspaceManifestService manifestService,
            FilePublicationRepository publications,
            FileCatalogService catalog,
            RuntimeBodyService bodies,
            ChatPersistenceService chats,
            DurableTaskLeaseService leases,
            io.agentscope.saas.domain.sandbox.SandboxLeaseRepository sandboxLeases,
            RunOrchestrationRepository runs,
            ObjectMapper mapper,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            WorkspaceSourceService sources) {
        this.archives = archives;
        this.manifests = manifests;
        this.manifestService = manifestService;
        this.publications = publications;
        this.catalog = catalog;
        this.bodies = bodies;
        this.chats = chats;
        this.leases = leases;
        this.sandboxLeases = sandboxLeases;
        this.runs = runs;
        this.mapper = mapper;
        this.properties = properties;
        this.sources = sources;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(10);
        var cfg = properties.getFileStore();
        if (cfg.getWorkspaceArchiveLeaseSeconds() < 1
                || cfg.getWorkspaceArchiveLeaseSeconds() > 3600
                || cfg.getWorkspaceArchiveDeadlineSeconds() <= cfg.getWorkspaceArchiveLeaseSeconds()
                || cfg.getWorkspaceArchiveDeadlineSeconds() > 86400
                || cfg.getWorkspaceArchiveMaxAttempts() < 1
                || cfg.getWorkspaceArchiveMaxAttempts() > 100
                || cfg.getWorkspaceArchiveRetrySeconds() < 1
                || cfg.getWorkspaceArchiveRetrySeconds() > 86400)
            throw new IllegalArgumentException("Invalid workspace archive policy");
    }

    public UUID defer(
            WorkspaceCheckpointContext checkpoint,
            String outputJson,
            List<ContentBlock> answer,
            boolean deliverReply) {
        return handover(checkpoint, outputJson, answer, deliverReply, false);
    }

    /** Joins the source-seal transaction; never performs object IO. */
    public UUID prepare(
            WorkspaceCheckpointContext checkpoint,
            String outputJson,
            List<ContentBlock> answer,
            boolean deliverReply) {
        var cfg = properties.getFileStore();
        if (!cfg.isWorkspaceCompletionHandoverEnabled()
                || !cfg.isWorkspaceArchiveEnabled()
                || !cfg.isPublicationRecoveryEnabled()) return null;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Prepared completion requires the source-seal transaction");
        return handover(checkpoint, outputJson, answer, deliverReply, true);
    }

    private UUID handover(
            WorkspaceCheckpointContext checkpoint,
            String outputJson,
            List<ContentBlock> answer,
            boolean deliverReply,
            boolean prepared) {
        if (!prepared && TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Archive handover must not carry transaction locks across IO");
        var cfg = properties.getFileStore();
        if (!cfg.isWorkspaceArchiveEnabled()
                || !cfg.isPublicationRecoveryEnabled()
                || checkpoint == null
                || checkpoint.manifest() == null)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_UNAVAILABLE");
        var admitted = checkpoint.manifest();
        var s = admitted.scope();
        var prior = archives.findAttempt(s).orElse(null);
        if (prior != null) {
            if (!prior.manifestId().equals(admitted.id()))
                throw new IllegalStateException("WORKSPACE_ARCHIVE_INVENTORY_MISMATCH");
            if (!prepared && archives.isPrepared(prior)) activatePrepared(prior, false);
            if (!("PENDING".equals(prior.status())
                    || "RUNNING".equals(prior.status())
                    || "COMPLETED".equals(prior.status())))
                throw new IllegalStateException("WORKSPACE_ARCHIVE_TERMINATED");
            return prior.id();
        }
        String json;
        try {
            json =
                    mapper.writeValueAsString(
                            new PendingResult(
                                    outputJson,
                                    answer == null ? List.of() : List.copyOf(answer),
                                    deliverReply));
        } catch (Exception error) {
            throw new IllegalStateException("WORKSPACE_ARCHIVE_RESULT_INVALID", error);
        }
        boolean inline =
                prepared
                        || (sources != null
                                && Boolean.TRUE.equals(
                                        transactions.execute(
                                                tx ->
                                                        sources.retained(
                                                                        admitted,
                                                                        OffsetDateTime.now())
                                                                .filter(
                                                                        source ->
                                                                                "provider-retention"
                                                                                        .equals(
                                                                                                source
                                                                                                        .backend()))
                                                                .isPresent())));
        if (inline && json.getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_INLINE_LIMIT");
        UUID bodyId = inline ? null : bodies.stage(bodyScope(s), json);
        var proposed = new java.util.concurrent.atomic.AtomicReference<Job>();
        try {
            return transactions.execute(
                    tx -> {
                        if (inline)
                            bodies.admitInline(
                                    bodyScope(s),
                                    json.getBytes(StandardCharsets.UTF_8).length,
                                    () -> archives.inlineBytes(s.orgId(), s.userId()),
                                    () -> archives.inlineBytes(s.orgId(), null));
                        OffsetDateTime now = OffsetDateTime.now();
                        if (!manifests.lockScope(s, now))
                            throw new SessionExecutionRevokedException();
                        var current =
                                manifests
                                        .lockCurrent(
                                                s.orgId(),
                                                s.runId(),
                                                s.execution().taskId(),
                                                s.execution().attemptId(),
                                                admitted.id())
                                        .orElseThrow();
                        if (!current.scope().equals(s)
                                || !current.scanComplete()
                                || !("SCANNED".equals(current.status())
                                        || "READY".equals(current.status())))
                            throw new IllegalStateException("WORKSPACE_ARCHIVE_SCAN_INCOMPLETE");
                        var entries = manifests.entries(s.orgId(), current.id());
                        var plan =
                                new io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan(
                                        entries.stream()
                                                .map(
                                                        e ->
                                                                new io.agentscope.harness.agent
                                                                        .sandbox
                                                                        .WorkspaceProjectionPlan
                                                                        .Entry(
                                                                        e.sourcePath(),
                                                                        e.logicalPath(),
                                                                        e.sizeBytes(),
                                                                        e.sha256(),
                                                                        e.unchanged()))
                                                .toList(),
                                        current.scannedFiles(),
                                        current.totalBytes());
                        if (!plan.version().equals(current.planHash())
                                || !current.planHash().equals(admitted.planHash()))
                            throw new IllegalStateException("WORKSPACE_ARCHIVE_INVENTORY_MISMATCH");
                        var entriesByPath =
                                entries.stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        e -> e.logicalPath(), e -> e));
                        var candidates =
                                new HashMap<String, FilePublicationRepository.Publication>();
                        OffsetDateTime deadline =
                                now.plusSeconds(cfg.getWorkspaceArchiveDeadlineSeconds());
                        var source =
                                sources == null
                                        ? null
                                        : sources.retained(current, now).orElse(null);
                        if (!prepared
                                && inline
                                && (source == null
                                        || !"provider-retention".equals(source.backend())))
                            throw new IllegalStateException("WORKSPACE_RETENTION_EXPIRED");
                        if (source != null && source.deadlineAt().isBefore(deadline))
                            deadline = source.deadlineAt();
                        for (var p : archives.materializedCandidates(s)) {
                            var expected = entriesByPath.get(p.logicalPath());
                            if ("PUBLISHED".equals(p.status())
                                    && expected != null
                                    && !p.id().equals(expected.publicationId())) continue;
                            var intent =
                                    publications
                                            .intent(p.orgId(), p.userId(), p.id())
                                            .orElseThrow();
                            try {
                                if (!mapper.readTree(intent.metadataJson())
                                        .path("workspaceManifestId")
                                        .asText()
                                        .equals(current.id().toString())) continue;
                            } catch (Exception malformed) {
                                throw new IllegalStateException(
                                        "WORKSPACE_ARCHIVE_INTENT_INVALID", malformed);
                            }
                            if (candidates.putIfAbsent(p.logicalPath(), p) != null)
                                throw new IllegalStateException(
                                        "WORKSPACE_ARCHIVE_SOURCE_AMBIGUOUS");
                            if ("STORED".equals(p.status())) {
                                var recovery =
                                        publications
                                                .recovery(p.orgId(), p.userId(), p.id())
                                                .orElseThrow();
                                if (recovery.recoveryToken() != null
                                        || recovery.recoveryDeadlineAt() == null
                                        || !recovery.recoveryDeadlineAt().isAfter(now))
                                    throw new IllegalStateException(
                                            "WORKSPACE_ARCHIVE_SOURCE_NOT_RETAINED");
                                if (recovery.recoveryDeadlineAt().isBefore(deadline))
                                    deadline = recovery.recoveryDeadlineAt();
                            }
                        }
                        if (entries.size() != current.fileCount()
                                || (prepared && source == null)
                                || (source == null && candidates.size() != entries.size()))
                            throw new IllegalStateException("WORKSPACE_ARCHIVE_BYTES_MISSING");
                        List<Item> items = new ArrayList<>();
                        for (var e : entries) {
                            var p = candidates.get(e.logicalPath());
                            if (p == null && source != null) {
                                items.add(new Item(e.logicalPath(), null));
                                continue;
                            }
                            if (p == null
                                    || p.sizeBytes() != e.sizeBytes()
                                    || !p.sha256().equals(e.sha256())
                                    || ("PUBLISHED".equals(p.status())
                                            && !p.id().equals(e.publicationId())))
                                throw new IllegalStateException(
                                        "WORKSPACE_ARCHIVE_SOURCE_MISMATCH");
                            items.add(new Item(e.logicalPath(), p.id()));
                        }
                        var job =
                                new Job(
                                        UUID.randomUUID(),
                                        s.orgId(),
                                        s.userId(),
                                        s.agentId(),
                                        s.sessionId(),
                                        s.sessionGeneration(),
                                        s.runId(),
                                        s.execution().taskId(),
                                        s.execution().agentRunId(),
                                        s.execution().attemptId(),
                                        s.execution().leaseOwner(),
                                        current.id(),
                                        bodyId,
                                        json.getBytes(StandardCharsets.UTF_8).length,
                                        "PENDING",
                                        null,
                                        null,
                                        deadline,
                                        prepared ? now.plusSeconds(30) : now,
                                        0,
                                        now);
                        if (prepared) archives.createPrepared(job, items, json);
                        else if (inline) archives.createInline(job, items, json);
                        else {
                            bodies.attach(bodyScope(s), bodyId);
                            archives.create(job, items);
                        }
                        if (!prepared && !archives.transition(job, OffsetDateTime.now()))
                            throw new SessionExecutionRevokedException();
                        if (prepared) return job.id();
                        UUID event = UUID.randomUUID();
                        String payload = "{\"archiveJobId\":\"" + job.id() + "\"}";
                        runs.insertEvent(
                                new NewEvent(
                                        event,
                                        s.orgId(),
                                        s.userId(),
                                        s.runId(),
                                        job.taskId(),
                                        runs.nextEventSequence(s.runId(), s.orgId(), now),
                                        "TASK_ARCHIVING",
                                        payload));
                        runs.insertOutbox(
                                new NewOutboxMessage(
                                        event,
                                        s.orgId(),
                                        s.runId(),
                                        "assistant_run",
                                        "task_archiving",
                                        payload));
                        proposed.set(job);
                        return job.id();
                    });
        } catch (RuntimeException error) {
            var expected = proposed.get();
            if (!prepared && expected != null) {
                try {
                    var committed =
                            transactions.execute(
                                    tx ->
                                            archives.lockOwned(expected.orgId(), expected.id())
                                                    .orElse(null));
                    if (committed != null
                            && committed.scope().equals(expected.scope())
                            && committed.manifestId().equals(expected.manifestId())
                            && java.util.Objects.equals(committed.bodyId(), expected.bodyId())
                            && committed.bodyBytes() == expected.bodyBytes()
                            && ("PENDING".equals(committed.status())
                                    || "RUNNING".equals(committed.status())
                                    || "COMPLETED".equals(committed.status())))
                        return committed.id();
                } catch (RuntimeException readError) {
                    if (readError != error) error.addSuppressed(readError);
                }
            }
            throw error;
        }
    }

    public boolean recover(Job candidate) {
        if (archives.isPrepared(candidate)) {
            var active = activatePrepared(candidate, true);
            return active != null && recover(active);
        }
        var cfg = properties.getFileStore();
        OffsetDateTime now = OffsetDateTime.now();
        if (!candidate.deadlineAt().isAfter(now)
                || candidate.attempts() >= cfg.getWorkspaceArchiveMaxAttempts())
            return leases.finishArchive(
                            candidate, null, null, "WORKSPACE_ARCHIVE_BUDGET_EXHAUSTED", archives)
                    || revokeIfStale(candidate);
        UUID token = UUID.randomUUID();
        Job job =
                transactions.execute(
                        tx -> {
                            if (!archives.lockScope(candidate)) {
                                archives.revoke(candidate, now);
                                return null;
                            }
                            var current =
                                    archives.lockOwned(candidate.orgId(), candidate.id())
                                            .orElseThrow();
                            if (!current.scope().equals(candidate.scope())
                                    || !current.manifestId().equals(candidate.manifestId()))
                                throw new SessionExecutionRevokedException();
                            OffsetDateTime until =
                                    now.plusSeconds(cfg.getWorkspaceArchiveLeaseSeconds());
                            if (until.isAfter(current.deadlineAt())) until = current.deadlineAt();
                            if (archives.claim(
                                            current,
                                            token,
                                            now,
                                            until,
                                            cfg.getWorkspaceArchiveMaxAttempts())
                                    != 1) return null;
                            return archives.lockOwned(candidate.orgId(), candidate.id())
                                    .orElseThrow();
                        });
        if (job == null) return false;
        Authority authority = new Authority(job.id(), token);
        try {
            var payload = mapper.readValue(readResult(job), PendingResult.class);
            if (sources != null) sources.restoreMissing(job, authority, catalog);
            for (var p : archives.publications(job)) {
                if ("PUBLISHED".equals(p.status())) continue;
                if (!"STORED".equals(p.status())
                        || !catalog.recoverPublication(p, token, authority, job.leaseUntil()))
                    throw new IllegalStateException("WORKSPACE_ARCHIVE_PUBLICATION_PENDING");
            }
            String output =
                    transactions.execute(
                            tx -> {
                                var receipts =
                                        manifestService.verifyArchived(job, authority, archives);
                                ObjectNode result =
                                        (ObjectNode)
                                                mapper.valueToTree(
                                                        parseOutput(payload.outputJson()));
                                String uri =
                                        io.agentscope.saas.app.orchestration
                                                .WorkspaceCheckpointReference.pinned(
                                                job.runId(), job.attemptId(), job.manifestId());
                                String version =
                                        WorkspaceManifestVersion.compute(
                                                receipts.stream()
                                                        .map(
                                                                r ->
                                                                        new WorkspaceManifestVersion
                                                                                .Entry(
                                                                                r.logicalPath(),
                                                                                r.versionId(),
                                                                                r.sha256()))
                                                        .toList());
                                sandboxLeases
                                        .findByAttemptId(job.attemptId(), job.orgId())
                                        .ifPresent(
                                                lease -> {
                                                    if (!job.userId().equals(lease.userId())
                                                            || !job.runId().equals(lease.runId())
                                                            || !job.taskId().equals(lease.taskId())
                                                            || sandboxLeases.checkpoint(
                                                                            lease.id(),
                                                                            job.orgId(),
                                                                            uri,
                                                                            version)
                                                                    != 1)
                                                        throw new IllegalStateException(
                                                                "WORKSPACE_ARCHIVE_CHECKPOINT_REJECTED");
                                                });
                                if (!receipts.isEmpty()) {
                                    var refs = result.withArray("artifactRefs");
                                    if (java.util.stream.StreamSupport.stream(
                                                    refs.spliterator(), false)
                                            .noneMatch(v -> uri.equals(v.asText()))) refs.add(uri);
                                    result.withArray("evidence")
                                            .addObject()
                                            .put("source", "workspace_checkpoint")
                                            .put("uri", uri)
                                            .put("version", version)
                                            .put("artifactCount", receipts.size());
                                }
                                return result.toString();
                            });
            if (payload.deliverReply() && leases.archiveCompletionPassed(job, output)) {
                transactions.executeWithoutResult(
                        tx -> {
                            if (!archives.lockAuthority(job, authority, OffsetDateTime.now()))
                                throw new SessionExecutionRevokedException();
                            chats.saveAssistantMessageForRun(
                                    new TenantContext(
                                            job.orgId().toString(),
                                            job.userId().toString(),
                                            "member",
                                            "standard",
                                            0,
                                            0),
                                    job.sessionId(),
                                    job.agentId(),
                                    job.runId(),
                                    payload.answer());
                        });
            }
            return leases.finishArchive(job, token, output, null, archives);
        } catch (Exception error) {
            transactions.executeWithoutResult(
                    tx -> {
                        if (!archives.lockScope(job)) archives.revoke(job, OffsetDateTime.now());
                        else {
                            var current = archives.lockOwned(job.orgId(), job.id()).orElseThrow();
                            var retryAt =
                                    OffsetDateTime.now()
                                            .plusSeconds(cfg.getWorkspaceArchiveRetrySeconds());
                            if ("RUNNING".equals(current.status())
                                    && token.equals(current.token())
                                    && current.leaseUntil().isAfter(OffsetDateTime.now())) {
                                for (var p : archives.publications(job)) {
                                    if ("STORED".equals(p.status()))
                                        publications.deferRecovery(
                                                p.orgId(),
                                                p.userId(),
                                                p.id(),
                                                token,
                                                OffsetDateTime.now(),
                                                retryAt);
                                }
                            }
                            archives.defer(
                                    job,
                                    token,
                                    OffsetDateTime.now(),
                                    OffsetDateTime.now()
                                            .plusSeconds(cfg.getWorkspaceArchiveRetrySeconds()));
                        }
                    });
            throw new IllegalStateException("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED", error);
        }
    }

    private boolean revokeIfStale(Job job) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        tx ->
                                !archives.lockScope(job)
                                        && archives.revoke(job, OffsetDateTime.now()) == 1));
    }

    private Job activatePrepared(Job candidate, boolean background) {
        return transactions.execute(
                tx -> {
                    var now = OffsetDateTime.now();
                    if (archives.settlePrepared(candidate, now) == 1) return null;
                    if (!archives.lockPreparedScope(candidate)) {
                        // Another consumer may already have activated it; it must still own the
                        // archive scope.
                        if (archives.lockScope(candidate))
                            return archives.lockOwned(candidate.orgId(), candidate.id())
                                    .orElseThrow();
                        throw new SessionExecutionRevokedException();
                    }
                    var current =
                            archives.lockOwned(candidate.orgId(), candidate.id()).orElseThrow();
                    if (!current.scope().equals(candidate.scope())
                            || !current.manifestId().equals(candidate.manifestId()))
                        throw new SessionExecutionRevokedException();
                    if (!archives.isPrepared(current)) return current;
                    if (background
                            && current.nextAttemptAt().isAfter(now)
                            && current.deadlineAt().isAfter(now)) return null;
                    if (!archives.activatePrepared(current, now))
                        throw new SessionExecutionRevokedException();
                    UUID event = UUID.randomUUID();
                    String payload = "{\"archiveJobId\":\"" + current.id() + "\"}";
                    runs.insertEvent(
                            new NewEvent(
                                    event,
                                    current.orgId(),
                                    current.userId(),
                                    current.runId(),
                                    current.taskId(),
                                    runs.nextEventSequence(current.runId(), current.orgId(), now),
                                    "TASK_ARCHIVING",
                                    payload));
                    runs.insertOutbox(
                            new NewOutboxMessage(
                                    event,
                                    current.orgId(),
                                    current.runId(),
                                    "assistant_run",
                                    "task_archiving",
                                    payload));
                    return archives.lockOwned(current.orgId(), current.id()).orElseThrow();
                });
    }

    public UUID adoptPrepared(WorkspaceCheckpointContext checkpoint) {
        if (checkpoint == null || checkpoint.manifest() == null) return null;
        var saved = archives.findAttempt(checkpoint.manifest().scope()).orElse(null);
        if (saved == null || !saved.manifestId().equals(checkpoint.manifest().id())) return null;
        if (archives.isPrepared(saved)) {
            var activated = activatePrepared(saved, false);
            return activated == null ? null : activated.id();
        }
        return "PENDING".equals(saved.status()) || "RUNNING".equals(saved.status())
                ? saved.id()
                : null;
    }

    private String readResult(Job job) {
        if (job.bodyId() != null)
            return bodies.read(bodyScope(job.scope()), job.bodyId(), job.bodyBytes());
        String json =
                archives.inlineResult(job)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "WORKSPACE_ARCHIVE_RESULT_MISSING"));
        int size = json.getBytes(StandardCharsets.UTF_8).length;
        if (size != job.bodyBytes() || size > 1048576)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_RESULT_INVALID");
        return json;
    }

    private ObjectNode parseOutput(String json) {
        try {
            var value = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!value.isObject()) throw new IllegalArgumentException();
            return (ObjectNode) value;
        } catch (Exception invalid) {
            throw new IllegalStateException("WORKSPACE_ARCHIVE_RESULT_INVALID", invalid);
        }
    }

    private static Scope bodyScope(WorkspaceManifestRepository.Scope scope) {
        return new Scope(
                scope.orgId(),
                scope.userId(),
                scope.agentId(),
                scope.sessionId(),
                "workspace-archive",
                scope.sessionId().toString());
    }

    public record PendingResult(
            String outputJson, List<ContentBlock> answer, boolean deliverReply) {}
}
