/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Authority;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Job;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Baseline;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Source;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable source escrow, with IO outside transactions and archive-only recovery authority. */
@Service
public class WorkspaceSourceService {
    private final WorkspaceSourceRepository sources;
    private final WorkspaceManifestRepository manifests;
    private final WorkspaceManifestService manifestService;
    private final WorkspaceArchiveRepository archives;
    private final OrgRepository orgs;
    private final FileRepository files;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;
    private final TransactionTemplate tx;
    private io.agentscope.harness.agent.sandbox.WorkspaceTransferResources transfers =
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources.DEFAULT;

    @org.springframework.beans.factory.annotation.Autowired
    void configureTransferResources(
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources resources) {
        transfers = resources;
    }

    private io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository retentions;
    private ObjectProvider<io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend>
            retentionBackends;

    @org.springframework.beans.factory.annotation.Autowired
    void configureRetention(
            io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository repository,
            ObjectProvider<io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend>
                    backends) {
        retentions = repository;
        retentionBackends = backends;
    }

    public WorkspaceSourceService(
            WorkspaceSourceRepository sources,
            WorkspaceManifestRepository manifests,
            WorkspaceManifestService manifestService,
            WorkspaceArchiveRepository archives,
            OrgRepository orgs,
            FileRepository files,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.sources = sources;
        this.manifests = manifests;
        this.manifestService = manifestService;
        this.archives = archives;
        this.orgs = orgs;
        this.files = files;
        this.stores = stores;
        this.properties = properties;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
        var cfg = properties.getFileStore();
        if (cfg.getWorkspaceSourceMaxBytes() < 1024
                || cfg.getWorkspaceSourceMaxBytes() > 536870912
                || cfg.getWorkspaceSourceMaxOrgBytes() < cfg.getWorkspaceSourceMaxBytes())
            throw new IllegalArgumentException("Invalid workspace source budget");
        if (cfg.getWorkspaceProviderRetentionSeconds() < 60
                || cfg.getWorkspaceProviderRetentionSeconds() > 3600
                || cfg.getWorkspaceProviderRetentionMaxOrgResources() < 1
                || cfg.getWorkspaceProviderRetentionMaxOrgResources() > 100)
            throw new IllegalArgumentException("Invalid workspace provider retention policy");
    }

    public boolean captureRetention(
            io.agentscope.core.agent.RuntimeContext context,
            Manifest manifest,
            WorkspaceProjectionPlan plan,
            Path path)
            throws Exception {
        return captureRetention(context, manifest, plan, path, () -> {});
    }

    public boolean captureRetention(
            io.agentscope.core.agent.RuntimeContext context,
            Manifest manifest,
            WorkspaceProjectionPlan plan,
            Path path,
            Runnable sealed)
            throws Exception {
        outside();
        var cfg = properties.getFileStore();
        var backend = retentionBackends == null ? null : retentionBackends.getIfAvailable();
        if (!cfg.isWorkspaceProviderRetentionEnabled()
                || !cfg.isWorkspaceArchiveEnabled()
                || !cfg.isPublicationRecoveryEnabled()
                || backend == null
                || retentions == null) return false;
        var sandbox = context.get(io.agentscope.harness.agent.sandbox.Sandbox.class);
        var tracking = context.get(io.agentscope.saas.sandbox.SandboxTrackingContext.class);
        var lease = context.get(io.agentscope.saas.sandbox.SandboxLeaseContext.class);
        if (sandbox == null || sandbox.getState() == null)
            throw new IllegalStateException("WORKSPACE_RETENTION_BINDING_MISSING");
        String resource =
                io.agentscope.saas.sandbox.SandboxExternalIds.fromRuntimeContext(context)
                        .orElseThrow();
        var scope = manifest.scope();
        long size = Files.size(path);
        String sha = hash(path);
        var retained =
                tx.execute(
                        status -> {
                            orgs.lockTenantOrg(scope.orgId()).orElseThrow();
                            if (!manifests.lockScope(scope, OffsetDateTime.now()))
                                throw new SessionExecutionRevokedException();
                            var source =
                                    sources.lockOwned(scope.orgId(), scope.userId(), manifest.id())
                                            .orElseThrow();
                            if (!"STAGING".equals(source.status())
                                    || source.token() != null
                                    || source.sizeBytes() != size
                                    || !source.sha256().equals(sha)
                                    || size > cfg.getWorkspaceSourceMaxBytes()
                                    || !source.deadlineAt().isAfter(OffsetDateTime.now()))
                                throw new IllegalStateException(
                                        "WORKSPACE_RETENTION_SOURCE_MISMATCH");
                            if (retentions.countHeld(scope.orgId())
                                    >= cfg.getWorkspaceProviderRetentionMaxOrgResources())
                                throw new IllegalStateException("WORKSPACE_RETENTION_QUOTA");
                            var deadline =
                                    OffsetDateTime.now()
                                            .plusSeconds(
                                                    cfg.getWorkspaceProviderRetentionSeconds());
                            if (deadline.isAfter(source.deadlineAt()))
                                deadline = source.deadlineAt();
                            deadline = deadline.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                            var binding =
                                    tracking != null && lease != null
                                            ? new io.agentscope.saas.domain.workspace
                                                    .WorkspaceRetentionRepository.Binding(
                                                    tracking.trackingId(), lease.leaseId())
                                            : retentionBinding(
                                                    scope, context, backend.providerId(), resource);
                            var value =
                                    new io.agentscope.saas.domain.workspace
                                            .WorkspaceRetentionRepository.Retention(
                                            source.id(),
                                            scope.orgId(),
                                            scope.userId(),
                                            manifest.id(),
                                            binding.trackingId(),
                                            binding.sandboxLeaseId(),
                                            backend.providerId(),
                                            resource,
                                            "STAGING",
                                            deadline,
                                            null,
                                            null);
                            retentions.stage(value);
                            return value;
                        });
        backend.seal(sandbox, retained.sourceId(), path, retained.deadlineAt());
        // The committed intent and confirmed provider TTL remain discoverable even if ready ACK
        // fails.
        context.put(
                io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold(
                        sandbox.getState().getSessionId(),
                        retained.sourceId(),
                        retained.deadlineAt()));
        try (var spool = transfers.spool("workspace-retention-verify-", Math.max(1, size))) {
            Path verification = spool.path();
            backend.retrieve(resource, retained.sourceId(), verification, size);
            if (Files.size(verification) != size || !hash(verification).equals(sha))
                throw new IllegalStateException("WORKSPACE_RETENTION_INTEGRITY");
        }
        tx.executeWithoutResult(
                status -> {
                    orgs.lockTenantOrg(scope.orgId()).orElseThrow();
                    if (!manifests.lockScope(scope, OffsetDateTime.now()))
                        throw new SessionExecutionRevokedException();
                    var current =
                            manifests
                                    .lockCurrent(
                                            scope.orgId(),
                                            scope.runId(),
                                            scope.execution().taskId(),
                                            scope.execution().attemptId(),
                                            manifest.id())
                                    .orElseThrow();
                    if (!current.planHash().equals(manifest.planHash())
                            || retentions.ready(retained, OffsetDateTime.now()) != 1)
                        throw new IllegalStateException("WORKSPACE_RETENTION_EXPIRED");
                    int unchanged =
                            Math.toIntExact(
                                    plan.entries().stream().filter(e -> e.unchanged()).count());
                    manifestService.scanCompleted(
                            manifest,
                            new WorkspaceProjectionReport(
                                    plan.scannedFiles(),
                                    plan.entries().size() - unchanged,
                                    unchanged,
                                    plan.totalBytes(),
                                    true,
                                    List.of()));
                    sealed.run();
                });
        return true;
    }

    private io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository.Binding
            retentionBinding(
                    WorkspaceManifestRepository.Scope scope,
                    io.agentscope.core.agent.RuntimeContext context,
                    String providerId,
                    String resourceId) {
        return retentions
                .binding(scope, context.getSessionId(), providerId, resourceId)
                .orElseThrow(
                        () -> new IllegalStateException("WORKSPACE_RETENTION_BINDING_MISSING"));
    }

    public boolean capture(Manifest manifest, WorkspaceProjectionPlan plan, Path path)
            throws Exception {
        return capture(manifest, plan, path, () -> {});
    }

    public boolean capture(
            Manifest manifest, WorkspaceProjectionPlan plan, Path path, Runnable sealed)
            throws Exception {
        outside();
        var cfg = properties.getFileStore();
        if (!cfg.isWorkspaceSourceEnabled() || !cfg.isWorkspaceArchiveEnabled() || !cfg.isEnabled())
            return false;
        FileObjectStore store =
                Objects.requireNonNull(
                        stores.getIfAvailable(), "Workspace source store unavailable");
        long size = Files.size(path);
        if (size < 1 || size > cfg.getWorkspaceSourceMaxBytes())
            throw new IllegalStateException("WORKSPACE_SOURCE_LIMIT");
        String sha = hash(path);
        var normalized =
                new WorkspaceProjectionPlan(
                        plan.entries().stream()
                                .map(
                                        e ->
                                                new WorkspaceProjectionPlan.Entry(
                                                        e.sourcePath()
                                                                .trim()
                                                                .replaceFirst("^/+", "")
                                                                .replaceFirst("/+$", ""),
                                                        e.path()
                                                                .trim()
                                                                .replaceFirst("^/+", "")
                                                                .replaceFirst("/+$", ""),
                                                        e.sizeBytes(),
                                                        e.sha256(),
                                                        e.unchanged()))
                                .toList(),
                        plan.scannedFiles(),
                        plan.totalBytes());
        var s = manifest.scope();
        var now = OffsetDateTime.now();
        UUID id = UUID.randomUUID();
        var deadline = now.plusSeconds(cfg.getWorkspaceArchiveDeadlineSeconds() + 600);
        var source =
                new Source(
                        id,
                        s.orgId(),
                        s.userId(),
                        manifest.id(),
                        prefix(s.orgId(), s.userId()) + id + ".tar",
                        store.backend(),
                        size,
                        sha,
                        "STAGING",
                        deadline,
                        deadline,
                        null,
                        null,
                        0,
                        now);
        tx.executeWithoutResult(
                status -> {
                    orgs.lockTenantOrg(s.orgId()).orElseThrow();
                    if (!manifests.lockScope(s, OffsetDateTime.now()))
                        throw new SessionExecutionRevokedException();
                    var current =
                            manifests
                                    .lockCurrent(
                                            s.orgId(),
                                            s.runId(),
                                            s.execution().taskId(),
                                            s.execution().attemptId(),
                                            manifest.id())
                                    .orElseThrow();
                    if (!current.scope().equals(s)
                            || !current.planHash().equals(normalized.version()))
                        throw new SessionExecutionRevokedException();
                    if (size > cfg.getWorkspaceSourceMaxOrgBytes() - sources.reserved(s.orgId()))
                        throw new IllegalStateException("WORKSPACE_SOURCE_QUOTA");
                    var baselines =
                            plan.entries().stream()
                                    .map(
                                            e -> {
                                                String p = e.path().replaceFirst("^/+", "");
                                                var f =
                                                        files.findByOrgIdAndUserIdAndLogicalPath(
                                                                s.orgId(), s.userId(), p);
                                                return new Baseline(
                                                        p,
                                                        f.map(v -> v.getId()).orElse(null),
                                                        f.map(v -> v.getCurrentVersionId())
                                                                .orElse(null),
                                                        f.map(v -> v.getStatus()).orElse(null));
                                            })
                                    .toList();
                    sources.stage(source, baselines);
                });
        store.putFromPath(s.orgId(), source.objectKey(), path, "application/x-tar", sha);
        try (var spool = transfers.spool("workspace-source-verify-", Math.max(1, size))) {
            Path verification = spool.path();
            store.copyToPath(s.orgId(), source.objectKey(), verification, size);
            if (Files.size(verification) != size || !sha.equals(hash(verification)))
                throw new IllegalStateException("WORKSPACE_SOURCE_INTEGRITY");
        }
        tx.executeWithoutResult(
                status -> {
                    orgs.lockTenantOrg(s.orgId()).orElseThrow();
                    if (!manifests.lockScope(s, OffsetDateTime.now()))
                        throw new SessionExecutionRevokedException();
                    manifests
                            .lockCurrent(
                                    s.orgId(),
                                    s.runId(),
                                    s.execution().taskId(),
                                    s.execution().attemptId(),
                                    manifest.id())
                            .orElseThrow();
                    if (sources.retain(source, OffsetDateTime.now()) != 1)
                        throw new IllegalStateException("WORKSPACE_SOURCE_EXPIRED");
                    int unchanged =
                            Math.toIntExact(
                                    plan.entries().stream().filter(e -> e.unchanged()).count());
                    // SCANNED means the complete byte inventory is frozen; READY still requires
                    // every receipt.
                    manifestService.scanCompleted(
                            manifest,
                            new WorkspaceProjectionReport(
                                    plan.scannedFiles(),
                                    plan.entries().size() - unchanged,
                                    unchanged,
                                    plan.totalBytes(),
                                    true,
                                    List.of()));
                    sealed.run();
                });
        return true;
    }

    /** Caller holds the same short transaction used to hand over the task. */
    public Optional<Source> retained(Manifest manifest, OffsetDateTime now) {
        var candidate =
                sources.lockOwned(
                        manifest.scope().orgId(), manifest.scope().userId(), manifest.id());
        if (candidate.isPresent()
                && retentions != null
                && "STAGING".equals(candidate.get().status())) {
            var source = candidate.get();
            var held =
                    retentions
                            .owned(source.orgId(), source.userId(), source.id())
                            .filter(
                                    r ->
                                            r.manifestId().equals(manifest.id())
                                                    && "RETAINED".equals(r.status())
                                                    && r.deadlineAt().isAfter(now)
                                                    && source.sizeBytes()
                                                            <= properties
                                                                    .getFileStore()
                                                                    .getWorkspaceSourceMaxBytes()
                                                    && source.deadlineAt().isAfter(now));
            if (held.isPresent())
                return Optional.of(
                        new Source(
                                source.id(),
                                source.orgId(),
                                source.userId(),
                                source.manifestId(),
                                source.objectKey(),
                                "provider-retention",
                                source.sizeBytes(),
                                source.sha256(),
                                "RETAINED",
                                held.get().deadlineAt(),
                                source.eligibleAt(),
                                null,
                                null,
                                source.attempts(),
                                source.createdAt()));
        }
        return candidate.filter(
                s ->
                        "RETAINED".equals(s.status())
                                && s.deadlineAt().isAfter(now)
                                && s.objectKey()
                                        .equals(prefix(s.orgId(), s.userId()) + s.id() + ".tar")
                                && s.sizeBytes()
                                        <= properties.getFileStore().getWorkspaceSourceMaxBytes());
    }

    public void restoreMissing(Job job, Authority authority, FileCatalogService catalog)
            throws Exception {
        outside();
        var input =
                tx.execute(
                        status -> {
                            if (!archives.lockAuthority(job, authority, OffsetDateTime.now()))
                                throw new SessionExecutionRevokedException();
                            var manifest =
                                    manifests
                                            .lockCurrent(
                                                    job.orgId(),
                                                    job.runId(),
                                                    job.taskId(),
                                                    job.attemptId(),
                                                    job.manifestId())
                                            .orElseThrow();
                            archives.bindPublishedItems(job, OffsetDateTime.now());
                            var items = archives.items(job);
                            var pubs =
                                    archives.publications(job).stream()
                                            .collect(
                                                    java.util.stream.Collectors.toMap(
                                                            p -> p.id(), p -> p));
                            var missing =
                                    items.stream()
                                            .filter(
                                                    i ->
                                                            i.publicationId() == null
                                                                    || !pubs.containsKey(
                                                                            i.publicationId())
                                                                    || !Set.of(
                                                                                    "STORED",
                                                                                    "PUBLISHED")
                                                                            .contains(
                                                                                    pubs.get(
                                                                                                    i
                                                                                                            .publicationId())
                                                                                            .status()))
                                            .map(i -> i.logicalPath())
                                            .collect(java.util.stream.Collectors.toSet());
                            if (missing.isEmpty()) return null;
                            var source =
                                    retained(manifest, OffsetDateTime.now())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "WORKSPACE_ARCHIVE_BYTES_MISSING"));
                            var entries = manifests.entries(job.orgId(), job.manifestId());
                            var plan =
                                    new WorkspaceProjectionPlan(
                                            entries.stream()
                                                    .map(
                                                            e ->
                                                                    new WorkspaceProjectionPlan
                                                                            .Entry(
                                                                            e.sourcePath(),
                                                                            e.logicalPath(),
                                                                            e.sizeBytes(),
                                                                            e.sha256(),
                                                                            e.unchanged()))
                                                    .toList(),
                                            manifest.scannedFiles(),
                                            manifest.totalBytes());
                            if (!plan.version().equals(manifest.planHash())
                                    || entries.size() != manifest.fileCount())
                                throw new IllegalStateException(
                                        "WORKSPACE_SOURCE_MANIFEST_MISMATCH");
                            var baselines = sources.baselines(source);
                            if (baselines.size() != entries.size())
                                throw new IllegalStateException(
                                        "WORKSPACE_SOURCE_BASELINE_MISMATCH");
                            return new Restore(source, entries, baselines, missing);
                        });
        if (input == null) return;
        try (var permit = transfers.acquire();
                var spool =
                        transfers.spool(
                                "workspace-source-restore-",
                                Math.max(1, input.source().sizeBytes()))) {
            Path path = spool.path();
            if ("provider-retention".equals(input.source().backend())) {
                var backend =
                        Objects.requireNonNull(
                                retentionBackends.getIfAvailable(),
                                "Provider retention backend unavailable");
                var held =
                        tx.execute(
                                status -> {
                                    if (!archives.lockAuthority(
                                            job, authority, OffsetDateTime.now()))
                                        throw new SessionExecutionRevokedException();
                                    return retentions
                                            .owned(job.orgId(), job.userId(), input.source().id())
                                            .filter(
                                                    r ->
                                                            "RETAINED".equals(r.status())
                                                                    && r.deadlineAt()
                                                                            .isAfter(
                                                                                    OffsetDateTime
                                                                                            .now())
                                                                    && r.providerId()
                                                                            .equals(
                                                                                    backend
                                                                                            .providerId())
                                                                    && r.manifestId()
                                                                            .equals(
                                                                                    job
                                                                                            .manifestId()))
                                            .orElseThrow();
                                });
                backend.retrieve(
                        held.resourceId(), held.sourceId(), path, input.source().sizeBytes());
            } else {
                FileObjectStore store = Objects.requireNonNull(stores.getIfAvailable());
                if (!input.source().backend().equals(store.backend()))
                    throw new IllegalStateException("WORKSPACE_SOURCE_BACKEND_CHANGED");
                store.copyToPath(
                        job.orgId(), input.source().objectKey(), path, input.source().sizeBytes());
            }
            if (Files.size(path) != input.source().sizeBytes()
                    || !hash(path).equals(input.source().sha256()))
                throw new IllegalStateException("WORKSPACE_SOURCE_INTEGRITY");
            // Validate the entire capture before publishing even the first missing file.
            visit(path, input.entries(), (entry, bytes) -> {});
            var bases =
                    input.baselines().stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            b -> b.logicalPath(), b -> b));
            visit(
                    path,
                    input.entries(),
                    (entry, bytes) -> {
                        if (input.missing().contains(entry.logicalPath()))
                            catalog.recordWorkspacePathForArchive(
                                    job,
                                    authority,
                                    entry.logicalPath(),
                                    bytes,
                                    bases.get(entry.logicalPath()));
                    });
        }
    }

    private void visit(Path path, List<WorkspaceManifestRepository.Entry> entries, Visitor visitor)
            throws Exception {
        var bySource =
                entries.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        e -> e.sourcePath().replaceFirst("^/+", ""), e -> e));
        Set<String> seen = new HashSet<>();
        try (var tar = new TarArchiveInputStream(Files.newInputStream(path))) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                var expected = bySource.get(e.getName());
                if (!e.isFile()
                        || e.isSymbolicLink()
                        || e.isLink()
                        || expected == null
                        || !seen.add(e.getName())
                        || e.getSize() != expected.sizeBytes())
                    throw new IllegalStateException("WORKSPACE_SOURCE_ENTRY_MISMATCH");
                try (var spool =
                        transfers.spool("workspace-source-entry-", Math.max(1, e.getSize()))) {
                    var digest = MessageDigest.getInstance("SHA-256");
                    long copied = 0;
                    byte[] buffer = new byte[transfers.bufferBytes()];
                    try (var out = spool.output()) {
                        int read;
                        while ((read = tar.read(buffer)) != -1) {
                            if (read > e.getSize() - copied)
                                throw new IllegalStateException("WORKSPACE_SOURCE_ENTRY_INTEGRITY");
                            copied += read;
                            digest.update(buffer, 0, read);
                            out.write(buffer, 0, read);
                        }
                    }
                    if (copied != e.getSize()
                            || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256()))
                        throw new IllegalStateException("WORKSPACE_SOURCE_ENTRY_INTEGRITY");
                    visitor.accept(expected, spool.path());
                }
            }
        }
        if (seen.size() != entries.size())
            throw new IllegalStateException("WORKSPACE_SOURCE_ENTRY_MISSING");
    }

    private record Restore(
            Source source,
            List<WorkspaceManifestRepository.Entry> entries,
            List<Baseline> baselines,
            Set<String> missing) {}

    private interface Visitor {
        void accept(WorkspaceManifestRepository.Entry entry, Path bytes) throws Exception;
    }

    static String prefix(UUID org, UUID user) {
        return "runtime/workspace-sources/org=" + org + "/user=" + user + "/";
    }

    static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Workspace source IO must be outside SQL transactions");
    }
}
