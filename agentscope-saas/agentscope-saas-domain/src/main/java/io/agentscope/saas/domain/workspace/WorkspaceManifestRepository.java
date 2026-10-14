/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.workspace;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Frozen expected workspace files; completion is based on exact durable publication receipts. */
public interface WorkspaceManifestRepository {
    record Scope(
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            long sessionGeneration,
            UUID runId,
            Execution execution) {
        public Scope {
            java.util.Objects.requireNonNull(orgId);
            java.util.Objects.requireNonNull(userId);
            java.util.Objects.requireNonNull(agentId);
            java.util.Objects.requireNonNull(sessionId);
            java.util.Objects.requireNonNull(runId);
            java.util.Objects.requireNonNull(execution);
            if (sessionGeneration < 0)
                throw new IllegalArgumentException("Invalid manifest generation");
        }
    }

    record Manifest(
            UUID id,
            Scope scope,
            long sequence,
            String planHash,
            int fileCount,
            long totalBytes,
            int scannedFiles,
            String status,
            boolean scanComplete,
            OffsetDateTime createdAt) {}

    record Entry(
            String sourcePath,
            String logicalPath,
            long sizeBytes,
            String sha256,
            boolean unchanged,
            UUID publicationId) {}

    record Inheritance(
            UUID leaseId,
            UUID parentTaskId,
            UUID parentAttemptId,
            String checkpointUri,
            String workspaceVersion) {}

    Optional<Inheritance> inheritance(Scope scope);

    Optional<UUID> parentTask(Scope scope);

    boolean isChildTask(Scope scope);

    void inherit(Scope scope, Inheritance checkpoint);

    boolean lockScope(Scope scope, OffsetDateTime now);

    long nextSequence(Scope scope);

    void create(Manifest manifest, List<Entry> entries);

    Optional<Manifest> lockCurrent(
            UUID orgId, UUID runId, UUID taskId, UUID attemptId, UUID manifestId);

    Optional<Scope> restoreScope(UUID orgId, UUID userId, UUID attemptId);

    Optional<Manifest> findCheckpoint(
            UUID orgId, UUID userId, UUID runId, UUID taskId, UUID attemptId, UUID manifestId);

    boolean hasAttemptManifests(UUID orgId, UUID userId, UUID runId, UUID taskId, UUID attemptId);

    List<Entry> entries(UUID orgId, UUID manifestId);

    List<ArtifactReceipt> receipts(Scope scope, UUID manifestId);

    int recordPublication(UUID manifestId, Publication publication, Intent intent);

    int scanCompleted(Scope scope, UUID manifestId, int projectedFiles, int unchangedFiles);

    int ready(UUID orgId, UUID manifestId);
}
