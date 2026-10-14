/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.app.workspace.FileCatalogService.StoredFile;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.SandboxLease;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds a verified, provider-neutral restore plan from the latest predecessor checkpoint. */
@Service
public class WorkspaceCheckpointRestoreService {

    private final SandboxLeaseRepository leaseRepository;
    private final RunArtifactRepository artifactRepository;
    private final FileCatalogService fileCatalogService;
    private final ObjectMapper objectMapper;
    private final WorkspaceManifestRepository manifests;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private RunExecutionPolicyService executionPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExecutionPolicies(RunExecutionPolicyService policies) {
        executionPolicies = policies;
    }

    private io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy policy(Scope scope) {
        return executionPolicies == null
                ? new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                        properties.getSandbox().getTransferMaxFiles(),
                        properties.getFileStore().getMaxFileBytes(),
                        properties.getSandbox().getTransferMaxBytes())
                : executionPolicies.workspacePolicy(scope.orgId(), scope.runId());
    }

    private io.agentscope.harness.agent.sandbox.WorkspaceTransferResources transfers =
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources.DEFAULT;

    @org.springframework.beans.factory.annotation.Autowired
    void configureTransferResources(
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources resources) {
        transfers = resources;
    }

    public WorkspaceCheckpointRestoreService(
            SandboxLeaseRepository leaseRepository,
            RunArtifactRepository artifactRepository,
            FileCatalogService fileCatalogService,
            ObjectMapper objectMapper,
            WorkspaceManifestRepository manifests,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.leaseRepository = leaseRepository;
        this.artifactRepository = artifactRepository;
        this.fileCatalogService = fileCatalogService;
        this.objectMapper = objectMapper;
        this.manifests = manifests;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Optional<WorkspaceRestorePlan> prepare(
            TenantContext tenant,
            UUID orgId,
            UUID currentAttemptId,
            String expectedLeaseOwner,
            WorkspaceIsolationMode isolationMode) {
        if (!requiresRestore(isolationMode)) {
            return Optional.empty();
        }
        if (tenant == null || !orgId.toString().equals(tenant.orgId()))
            throw new SessionExecutionRevokedException();
        UUID userId = UUID.fromString(tenant.userId());
        Scope scope =
                transactions.execute(
                        tx -> {
                            Scope current =
                                    manifests
                                            .restoreScope(orgId, userId, currentAttemptId)
                                            .orElseThrow(SessionExecutionRevokedException::new);
                            if (!Objects.equals(
                                    expectedLeaseOwner, current.execution().leaseOwner()))
                                throw new SessionExecutionRevokedException();
                            requireCurrent(current);
                            return current;
                        });
        RestoreMetadata metadata =
                transactions.execute(
                        tx -> {
                            requireCurrent(scope);
                            SandboxLease checkpoint =
                                    leaseRepository
                                            .findLatestCheckpointBeforeAttempt(
                                                    currentAttemptId, orgId)
                                            .orElse(null);
                            if (checkpoint == null) {
                                var inherited = manifests.inheritance(scope);
                                if (inherited.isEmpty()) {
                                    var parent =
                                            leaseRepository.findParentCheckpoint(
                                                    currentAttemptId, orgId);
                                    if (parent.isPresent()) {
                                        var p = parent.orElseThrow();
                                        var binding =
                                                new WorkspaceManifestRepository.Inheritance(
                                                        p.id(),
                                                        p.taskId(),
                                                        p.attemptId(),
                                                        p.workspaceSnapshotUri(),
                                                        p.workspaceVersion());
                                        manifests.inherit(scope, binding);
                                        inherited = Optional.of(binding);
                                    } else if (manifests.parentTask(scope).isPresent())
                                        throw new IllegalStateException(
                                                "PARENT_WORKSPACE_CHECKPOINT_UNAVAILABLE");
                                }
                                if (inherited.isPresent())
                                    checkpoint = inheritedLease(scope, inherited.orElseThrow());
                            }
                            return checkpoint == null ? null : load(scope, checkpoint);
                        });
        if (metadata == null) return Optional.empty();
        var policy = policy(scope);

        List<WorkspaceFile> files = new ArrayList<>(metadata.entries().size());
        List<WorkspaceManifestVersion.Entry> manifest = new ArrayList<>(metadata.entries().size());
        long restoredBytes = 0;
        // Object-store GETs must not retain SQL locks; recheck execution and the exact snapshot
        // afterwards.
        for (RestoreEntry entry : metadata.entries()) {
            var descriptor = fileCatalogService.describeVersion(tenant, entry.versionId());
            if (descriptor.isPresent()) {
                var stored = descriptor.orElseThrow();
                if ((entry.sha256() != null && !entry.sha256().equals(stored.sha256()))
                        || (entry.sizeBytes() >= 0 && entry.sizeBytes() != stored.sizeBytes()))
                    throw new IllegalStateException("WORKSPACE_CHECKPOINT_RESTORE_INTEGRITY");
                restoredBytes = Math.addExact(restoredBytes, stored.sizeBytes());
                if (stored.sizeBytes() > policy.maxFileBytes()
                        || restoredBytes > policy.maxTotalBytes())
                    throw new IllegalStateException("WORKSPACE_CHECKPOINT_RESTORE_LIMIT");
                var file =
                        new WorkspaceFile(
                                entry.sourcePath(),
                                stored.sizeBytes(),
                                stored.sha256(),
                                () -> {
                                    validateCurrent(scope);
                                    var stream = stored.source().open();
                                    return new java.io.FilterInputStream(stream) {
                                        @Override
                                        public void close() throws java.io.IOException {
                                            try {
                                                super.close();
                                            } finally {
                                                validateCurrent(scope);
                                            }
                                        }
                                    };
                                });
                try (var permit = transfers.acquire()) {
                    file.transferTo(
                            java.io.OutputStream.nullOutputStream(), transfers.bufferBytes());
                } catch (SessionExecutionRevokedException revoked) {
                    throw revoked;
                } catch (Exception error) {
                    throw new IllegalStateException(
                            "Workspace artifact content digest mismatch", error);
                }
                files.add(file);
                manifest.add(
                        new WorkspaceManifestVersion.Entry(
                                entry.logicalPath(), entry.versionId(), stored.sha256()));
                continue;
            }
            StoredFile stored =
                    fileCatalogService
                            .readVersion(tenant, entry.versionId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Workspace artifact version is unavailable: "
                                                            + entry.versionId()));
            if (stored.sha256() == null || stored.sha256().isBlank()) {
                throw new IllegalStateException(
                        "Workspace artifact has no content digest: " + entry.versionId());
            }
            String actualSha256 = sha256(stored.content());
            if (!actualSha256.equals(stored.sha256())
                    || stored.content() == null
                    || stored.sizeBytes() == null
                    || stored.sizeBytes() != stored.content().length
                    || (entry.sha256() != null && !entry.sha256().equals(actualSha256))
                    || (entry.sizeBytes() >= 0 && entry.sizeBytes() != stored.content().length)) {
                throw new IllegalStateException(
                        "Workspace artifact content digest mismatch: " + entry.versionId());
            }
            restoredBytes = Math.addExact(restoredBytes, stored.content().length);
            if (stored.content().length > policy.maxFileBytes()
                    || restoredBytes > policy.maxTotalBytes())
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_RESTORE_LIMIT");
            files.add(new WorkspaceFile(entry.sourcePath(), stored.content()));
            manifest.add(
                    new WorkspaceManifestVersion.Entry(
                            entry.logicalPath(), entry.versionId(), stored.sha256()));
        }

        String actualVersion = WorkspaceManifestVersion.compute(manifest);
        if (!actualVersion.equals(metadata.checkpoint().workspaceVersion())) {
            throw new IllegalStateException(
                    "Workspace checkpoint manifest mismatch for "
                            + metadata.checkpoint().attemptId());
        }
        transactions.executeWithoutResult(
                tx -> {
                    requireCurrent(scope);
                    SandboxLease current =
                            leaseRepository
                                    .findById(metadata.checkpoint().id(), orgId)
                                    .orElseThrow(SessionExecutionRevokedException::new);
                    if (!scope.execution().taskId().equals(current.taskId()))
                        current =
                                inheritedLease(
                                        scope,
                                        manifests
                                                .inheritance(scope)
                                                .orElseThrow(
                                                        SessionExecutionRevokedException::new));
                    if (!Objects.equals(
                                    current.workspaceSnapshotUri(),
                                    metadata.checkpoint().workspaceSnapshotUri())
                            || !Objects.equals(
                                    current.workspaceVersion(),
                                    metadata.checkpoint().workspaceVersion())
                            || !load(scope, current).entries().equals(metadata.entries()))
                        throw new IllegalStateException(
                                "WORKSPACE_CHECKPOINT_CHANGED_DURING_RESTORE");
                    requireCurrent(scope);
                });
        return Optional.of(
                new WorkspaceRestorePlan(
                        metadata.checkpoint().workspaceSnapshotUri(),
                        metadata.checkpoint().workspaceVersion(),
                        files));
    }

    private void requireCurrent(Scope scope) {
        // Refresh time after acquiring locks so a long SQL wait cannot revive an expired lease.
        if (!manifests.lockScope(scope, OffsetDateTime.now())
                || !manifests.lockScope(scope, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
    }

    private void validateCurrent(Scope scope) {
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(scope.orgId().toString());
        try {
            transactions.executeWithoutResult(tx -> requireCurrent(scope));
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    private RestoreMetadata load(Scope current, SandboxLease checkpoint) {
        var policy = policy(current);
        if (!current.orgId().equals(checkpoint.orgId())
                || !current.userId().equals(checkpoint.userId())
                || !current.runId().equals(checkpoint.runId())
                || (!current.execution().taskId().equals(checkpoint.taskId())
                        && !isInherited(current, checkpoint))
                || current.execution().attemptId().equals(checkpoint.attemptId()))
            throw new SessionExecutionRevokedException();
        UUID manifestId =
                WorkspaceCheckpointReference.manifest(
                        checkpoint.workspaceSnapshotUri(),
                        checkpoint.runId(),
                        checkpoint.attemptId());
        List<RestoreEntry> entries;
        if (manifestId == null) {
            if (manifests.hasAttemptManifests(
                    checkpoint.orgId(),
                    checkpoint.userId(),
                    checkpoint.runId(),
                    checkpoint.taskId(),
                    checkpoint.attemptId()))
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_MANIFEST_REFERENCE_REQUIRED");
            entries =
                    artifactRepository
                            .findByAttemptId(checkpoint.attemptId(), checkpoint.orgId())
                            .stream()
                            .filter(
                                    a ->
                                            WorkspaceArtifactService.ARTIFACT_TYPE_WORKSPACE_FILE
                                                    .equals(a.artifactType()))
                            .map(
                                    a -> {
                                        if (!checkpoint.runId().equals(a.runId())
                                                || !checkpoint.taskId().equals(a.taskId()))
                                            throw new SessionExecutionRevokedException();
                                        return new RestoreEntry(
                                                logicalPath(a), a.fileVersionId(), null, -1);
                                    })
                            .toList();
        } else {
            var admitted =
                    manifests
                            .findCheckpoint(
                                    checkpoint.orgId(),
                                    checkpoint.userId(),
                                    checkpoint.runId(),
                                    checkpoint.taskId(),
                                    checkpoint.attemptId(),
                                    manifestId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "WORKSPACE_CHECKPOINT_MANIFEST_UNAVAILABLE"));
            Scope source = admitted.scope();
            if (!current.orgId().equals(source.orgId())
                    || !current.userId().equals(source.userId())
                    || !current.runId().equals(source.runId())
                    || !current.agentId().equals(source.agentId())
                    || !current.sessionId().equals(source.sessionId())
                    || current.sessionGeneration() != source.sessionGeneration()
                    || !manifestId.equals(admitted.id())
                    || !checkpoint.taskId().equals(source.execution().taskId())
                    || !checkpoint.attemptId().equals(source.execution().attemptId()))
                throw new SessionExecutionRevokedException();
            if (!"READY".equals(admitted.status()) || !admitted.scanComplete())
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_MANIFEST_NOT_READY");
            if (admitted.fileCount() > policy.maxFiles()
                    || admitted.totalBytes() > policy.maxTotalBytes())
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_RESTORE_LIMIT");
            var inventory = manifests.entries(checkpoint.orgId(), manifestId);
            var plan =
                    new WorkspaceProjectionPlan(
                            inventory.stream()
                                    .map(
                                            e ->
                                                    new WorkspaceProjectionPlan.Entry(
                                                            safePath(e.sourcePath()),
                                                            safePath(e.logicalPath()),
                                                            e.sizeBytes(),
                                                            e.sha256(),
                                                            e.unchanged()))
                                    .toList(),
                            admitted.scannedFiles(),
                            admitted.totalBytes());
            if (inventory.size() != admitted.fileCount()
                    || !plan.version().equals(admitted.planHash()))
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_INVENTORY_MISMATCH");
            var receipts =
                    manifests.receipts(source, manifestId).stream()
                            .collect(Collectors.toMap(r -> r.logicalPath(), r -> r));
            entries =
                    inventory.stream()
                            .map(
                                    e -> {
                                        var r = receipts.get(e.logicalPath());
                                        if (e.publicationId() == null
                                                || r == null
                                                || r.versionId() == null
                                                || !e.sha256().equals(r.sha256())
                                                || e.sizeBytes() != r.sizeBytes())
                                            throw new IllegalStateException(
                                                    "WORKSPACE_CHECKPOINT_RECEIPT_MISSING_OR_MISMATCHED");
                                        if (e.sizeBytes() > policy.maxFileBytes())
                                            throw new IllegalStateException(
                                                    "WORKSPACE_CHECKPOINT_RESTORE_LIMIT");
                                        return new RestoreEntry(
                                                e.logicalPath(),
                                                r.versionId(),
                                                r.sha256(),
                                                r.sizeBytes(),
                                                e.sourcePath());
                                    })
                            .toList();
            if (receipts.size() != entries.size()
                    || !WorkspaceManifestVersion.compute(
                                    entries.stream()
                                            .map(
                                                    e ->
                                                            new WorkspaceManifestVersion.Entry(
                                                                    e.logicalPath(),
                                                                    e.versionId(),
                                                                    e.sha256()))
                                            .toList())
                            .equals(checkpoint.workspaceVersion()))
                throw new IllegalStateException(
                        "Workspace checkpoint manifest mismatch for " + checkpoint.attemptId());
        }
        Set<String> paths = new HashSet<>();
        Set<String> sources = new HashSet<>();
        if (entries.size() > policy.maxFiles())
            throw new IllegalStateException("WORKSPACE_CHECKPOINT_RESTORE_LIMIT");
        for (var entry : entries) {
            if (entry.versionId() == null
                    || !paths.add(entry.logicalPath())
                    || !sources.add(entry.sourcePath()))
                throw new IllegalStateException(
                        "Workspace checkpoint contains duplicate path or missing version "
                                + entry.logicalPath());
        }
        return new RestoreMetadata(checkpoint, List.copyOf(entries));
    }

    private record RestoreMetadata(SandboxLease checkpoint, List<RestoreEntry> entries) {}

    private boolean isInherited(Scope scope, SandboxLease lease) {
        return manifests
                .inheritance(scope)
                .filter(
                        i ->
                                i.leaseId().equals(lease.id())
                                        && i.parentTaskId().equals(lease.taskId())
                                        && i.parentAttemptId().equals(lease.attemptId())
                                        && i.checkpointUri().equals(lease.workspaceSnapshotUri())
                                        && i.workspaceVersion().equals(lease.workspaceVersion()))
                .isPresent();
    }

    private SandboxLease inheritedLease(Scope scope, WorkspaceManifestRepository.Inheritance i) {
        var p =
                leaseRepository
                        .findById(i.leaseId(), scope.orgId())
                        .orElseThrow(SessionExecutionRevokedException::new);
        if (!p.runId().equals(scope.runId())
                || !p.userId().equals(scope.userId())
                || !p.taskId().equals(i.parentTaskId())
                || !p.attemptId().equals(i.parentAttemptId()))
            throw new SessionExecutionRevokedException();
        return new SandboxLease(
                p.id(),
                p.orgId(),
                p.userId(),
                p.runId(),
                p.taskId(),
                p.attemptId(),
                p.providerId(),
                p.providerSandboxId(),
                p.providerStateJson(),
                p.imageOrTemplate(),
                p.capabilitiesJson(),
                i.checkpointUri(),
                i.workspaceVersion(),
                p.status(),
                p.leaseOwner(),
                p.leaseExpiresAt(),
                p.lastHeartbeatAt(),
                p.createdAt(),
                p.releasedAt(),
                p.releaseError());
    }

    private record RestoreEntry(
            String logicalPath, UUID versionId, String sha256, long sizeBytes, String sourcePath) {
        RestoreEntry(String logicalPath, UUID versionId, String sha256, long sizeBytes) {
            this(logicalPath, versionId, sha256, sizeBytes, logicalPath);
        }
    }

    private static String safePath(String value) {
        if (value == null
                || value.isBlank()
                || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0)
            throw new IllegalStateException("WORKSPACE_CHECKPOINT_UNSAFE_PATH");
        Path path = Path.of(value).normalize();
        if (path.isAbsolute()
                || path.startsWith("..")
                || !path.toString().replace('\\', '/').equals(value))
            throw new IllegalStateException("WORKSPACE_CHECKPOINT_UNSAFE_PATH");
        return value;
    }

    private String logicalPath(RunArtifact artifact) {
        String path = artifact.logicalPath();
        if (path == null || path.isBlank()) {
            try {
                path = objectMapper.readTree(artifact.evidenceJson()).path("logicalPath").asText();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Workspace artifact has invalid restore evidence: " + artifact.id(), e);
            }
        }
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "Workspace artifact has no logical path: " + artifact.id());
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalStateException(
                        "Workspace artifact path escapes the workspace: " + path);
            }
        }
        Path safe = Path.of(normalized).normalize();
        if (safe.isAbsolute() || safe.startsWith("..") || safe.toString().isBlank()) {
            throw new IllegalStateException(
                    "Workspace artifact path escapes the workspace: " + path);
        }
        return safe.toString().replace('\\', '/');
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content != null ? content : new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean requiresRestore(WorkspaceIsolationMode mode) {
        return mode == WorkspaceIsolationMode.RUN_ISOLATED
                || mode == WorkspaceIsolationMode.ATTEMPT_ISOLATED
                || mode == WorkspaceIsolationMode.DEDICATED_SANDBOX;
    }
}
