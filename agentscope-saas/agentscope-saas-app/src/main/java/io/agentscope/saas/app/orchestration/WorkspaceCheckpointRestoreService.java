/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.app.workspace.FileCatalogService.StoredFile;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.SandboxLease;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a verified, provider-neutral restore plan from the latest predecessor checkpoint. */
@Service
public class WorkspaceCheckpointRestoreService {

    private final SandboxLeaseRepository leaseRepository;
    private final RunArtifactRepository artifactRepository;
    private final FileCatalogService fileCatalogService;
    private final ObjectMapper objectMapper;

    public WorkspaceCheckpointRestoreService(
            SandboxLeaseRepository leaseRepository,
            RunArtifactRepository artifactRepository,
            FileCatalogService fileCatalogService,
            ObjectMapper objectMapper) {
        this.leaseRepository = leaseRepository;
        this.artifactRepository = artifactRepository;
        this.fileCatalogService = fileCatalogService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceRestorePlan> prepare(
            TenantContext tenant,
            UUID orgId,
            UUID currentAttemptId,
            WorkspaceIsolationMode isolationMode) {
        if (!requiresRestore(isolationMode)) {
            return Optional.empty();
        }
        SandboxLease checkpoint =
                leaseRepository
                        .findLatestCheckpointBeforeAttempt(currentAttemptId, orgId)
                        .orElse(null);
        if (checkpoint == null) {
            return Optional.empty();
        }

        List<RunArtifact> artifacts =
                artifactRepository.findByAttemptId(checkpoint.attemptId(), orgId).stream()
                        .filter(
                                artifact ->
                                        WorkspaceArtifactService.ARTIFACT_TYPE_WORKSPACE_FILE
                                                .equals(artifact.artifactType()))
                        .toList();
        List<WorkspaceFile> files = new ArrayList<>(artifacts.size());
        List<WorkspaceManifestVersion.Entry> manifest = new ArrayList<>(artifacts.size());
        Set<String> paths = new HashSet<>();
        for (RunArtifact artifact : artifacts) {
            String logicalPath = logicalPath(artifact);
            if (!paths.add(logicalPath)) {
                throw new IllegalStateException(
                        "Workspace checkpoint contains duplicate path " + logicalPath);
            }
            StoredFile stored =
                    fileCatalogService
                            .readVersion(tenant, artifact.fileVersionId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Workspace artifact version is unavailable: "
                                                            + artifact.fileVersionId()));
            if (stored.sha256() == null || stored.sha256().isBlank()) {
                throw new IllegalStateException(
                        "Workspace artifact has no content digest: " + artifact.fileVersionId());
            }
            String actualSha256 = sha256(stored.content());
            if (!actualSha256.equals(stored.sha256())) {
                throw new IllegalStateException(
                        "Workspace artifact content digest mismatch: " + artifact.fileVersionId());
            }
            files.add(new WorkspaceFile(logicalPath, stored.content()));
            manifest.add(
                    new WorkspaceManifestVersion.Entry(
                            logicalPath, artifact.fileVersionId(), stored.sha256()));
        }

        String actualVersion = WorkspaceManifestVersion.compute(manifest);
        if (!actualVersion.equals(checkpoint.workspaceVersion())) {
            throw new IllegalStateException(
                    "Workspace checkpoint manifest mismatch for " + checkpoint.attemptId());
        }
        return Optional.of(
                new WorkspaceRestorePlan(
                        checkpoint.workspaceSnapshotUri(), checkpoint.workspaceVersion(), files));
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
