/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.app.workspace.WorkspaceManifestService;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.sandbox.SandboxLeaseContext;
import io.agentscope.saas.sandbox.SandboxLeaseService;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one provider-neutral workspace checkpoint and its immutable file artifacts. */
@Service
public class WorkspaceArtifactService {

    public static final String ARTIFACT_TYPE_WORKSPACE_FILE = "WORKSPACE_FILE";

    private final RunArtifactRepository artifactRepository;
    private final SandboxLeaseService sandboxLeaseService;
    private final FilePublicationRepository publications;
    private final WorkspaceManifestService manifests;

    public WorkspaceArtifactService(
            RunArtifactRepository artifactRepository,
            SandboxLeaseService sandboxLeaseService,
            FilePublicationRepository publications,
            WorkspaceManifestService manifests) {
        this.artifactRepository = artifactRepository;
        this.sandboxLeaseService = sandboxLeaseService;
        this.publications = publications;
        this.manifests = manifests;
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
        if (!artifactRepository.lockCurrentPublicationScope(orgId, runId, taskId, attemptId))
            throw new SessionExecutionRevokedException();
        List<FileRecord> files =
                checkpoint.files().stream()
                        .sorted(Comparator.comparing(FileRecord::logicalPath))
                        .toList();
        var receipts = new HashMap<UUID, ArtifactReceipt>();
        for (var receipt : publications.artifactReceipts(orgId, runId, taskId, attemptId)) {
            if (receipts.putIfAbsent(receipt.versionId(), receipt) != null)
                throw new IllegalStateException("FILE_ARTIFACT_RECEIPT_CONFLICT");
        }
        var paths = new HashSet<String>();
        for (FileRecord file : files) {
            if (!paths.add(file.logicalPath()))
                throw new IllegalStateException("WORKSPACE_CHECKPOINT_DUPLICATE_FILE");
            ArtifactReceipt receipt = receipts.get(file.versionId());
            if (receipt == null
                    || !Objects.equals(receipt.fileId(), file.fileId())
                    || !Objects.equals(receipt.logicalPath(), file.logicalPath())
                    || !Objects.equals(receipt.objectKey(), file.objectKey())
                    || !Objects.equals(receipt.backend(), file.storageBackend())
                    || !Objects.equals(receipt.sha256(), file.sha256())
                    || receipt.sizeBytes() != file.sizeBytes())
                throw new IllegalStateException("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
        }

        manifests.verifyAndReady(orgId, runId, taskId, attemptId, checkpoint.manifest(), files);
        String version =
                WorkspaceManifestVersion.compute(
                        files.stream()
                                .map(
                                        file ->
                                                new WorkspaceManifestVersion.Entry(
                                                        file.logicalPath(),
                                                        file.versionId(),
                                                        file.sha256()))
                                .toList());
        String uri =
                WorkspaceCheckpointReference.pinned(runId, attemptId, checkpoint.manifest().id());
        if (!sandboxLeaseService.checkpointForAttempt(
                lease,
                orgId,
                checkpoint.manifest().scope().userId(),
                runId,
                taskId,
                attemptId,
                uri,
                version)) {
            throw new IllegalStateException(
                    "Sandbox lease rejected workspace checkpoint for attempt " + attemptId);
        }
        return new Publication(uri, version, files.size());
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(UUID orgId, UUID runId) {
        return artifactRepository.findByRunId(runId, orgId).stream()
                .map(WorkspaceArtifactService::toView)
                .toList();
    }

    private static ArtifactView toView(RunArtifact artifact) {
        return new ArtifactView(
                artifact.id(),
                artifact.taskId(),
                artifact.attemptId(),
                artifact.fileId(),
                artifact.fileVersionId(),
                artifact.logicalPath(),
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
            String logicalPath,
            String artifactType,
            String evidenceJson,
            OffsetDateTime createdAt) {}
}
