/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.NewRunArtifact;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.sandbox.SandboxLeaseContext;
import io.agentscope.saas.sandbox.SandboxLeaseService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one provider-neutral workspace checkpoint and its immutable file artifacts. */
@Service
public class WorkspaceArtifactService {

    public static final String ARTIFACT_TYPE_WORKSPACE_FILE = "WORKSPACE_FILE";

    private final RunArtifactRepository artifactRepository;
    private final SandboxLeaseService sandboxLeaseService;
    private final ObjectMapper objectMapper;

    public WorkspaceArtifactService(
            RunArtifactRepository artifactRepository,
            SandboxLeaseService sandboxLeaseService,
            ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.sandboxLeaseService = sandboxLeaseService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Publication publish(
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            SandboxLeaseContext lease,
            WorkspaceCheckpointContext checkpoint) {
        checkpoint.verifyReady();
        List<FileRecord> files =
                checkpoint.files().stream()
                        .sorted(Comparator.comparing(FileRecord::logicalPath))
                        .toList();
        OffsetDateTime now = OffsetDateTime.now();
        for (FileRecord file : files) {
            UUID artifactId = artifactId(attemptId, file.versionId());
            if (artifactRepository.existsById(artifactId, orgId)) {
                continue;
            }
            int inserted =
                    artifactRepository.insert(
                            new NewRunArtifact(
                                    artifactId,
                                    orgId,
                                    runId,
                                    taskId,
                                    attemptId,
                                    file.fileId(),
                                    file.versionId(),
                                    ARTIFACT_TYPE_WORKSPACE_FILE,
                                    evidence(file, checkpoint),
                                    now));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Run artifact " + artifactId + " insert affected " + inserted + " rows");
            }
        }

        String version = manifestVersion(files);
        String uri = "workspace-catalog://runs/" + runId + "/attempts/" + attemptId;
        if (lease != null && !sandboxLeaseService.checkpoint(lease, uri, version)) {
            throw new IllegalStateException(
                    "Sandbox lease " + lease.leaseId() + " rejected workspace checkpoint");
        }
        return new Publication(uri, version, files.size());
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(UUID orgId, UUID runId) {
        return artifactRepository.findByRunId(runId, orgId).stream()
                .map(WorkspaceArtifactService::toView)
                .toList();
    }

    private String evidence(FileRecord file, WorkspaceCheckpointContext checkpoint) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(
                            "logicalPath",
                            file.logicalPath(),
                            "versionNo",
                            file.versionNo(),
                            "sha256",
                            file.sha256(),
                            "sizeBytes",
                            file.sizeBytes(),
                            "storageBackend",
                            file.storageBackend(),
                            "sandboxStatePersisted",
                            checkpoint.stateWasPersisted(),
                            "sandboxStopped",
                            checkpoint.sandboxWasStopped()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workspace artifact evidence", e);
        }
    }

    private static UUID artifactId(UUID attemptId, UUID fileVersionId) {
        return UUID.nameUUIDFromBytes(
                ("workspace-file:" + attemptId + ":" + fileVersionId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String manifestVersion(List<FileRecord> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (FileRecord file : files) {
                digest.update(file.logicalPath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.versionId().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.sha256().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ArtifactView toView(RunArtifact artifact) {
        return new ArtifactView(
                artifact.id(),
                artifact.taskId(),
                artifact.attemptId(),
                artifact.fileId(),
                artifact.fileVersionId(),
                artifact.artifactType(),
                artifact.evidenceJson(),
                artifact.createdAt());
    }

    public record Publication(String uri, String version, int artifactCount) {}

    public record ArtifactView(
            UUID id,
            UUID taskId,
            UUID attemptId,
            UUID fileId,
            UUID fileVersionId,
            String artifactType,
            String evidenceJson,
            OffsetDateTime createdAt) {}
}
