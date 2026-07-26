/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped persistence port for immutable artifacts published by execution attempts. */
public interface RunArtifactRepository {

    boolean existsById(UUID id, UUID orgId);

    int insert(NewRunArtifact artifact);

    List<RunArtifact> findByRunId(UUID runId, UUID orgId);

    record NewRunArtifact(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            UUID fileId,
            UUID fileVersionId,
            String artifactType,
            String evidenceJson,
            OffsetDateTime createdAt) {}

    record RunArtifact(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            UUID fileId,
            UUID fileVersionId,
            String artifactType,
            String evidenceJson,
            OffsetDateTime createdAt) {}
}
