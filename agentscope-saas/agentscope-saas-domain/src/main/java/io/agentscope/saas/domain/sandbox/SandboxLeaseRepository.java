/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.sandbox;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence port for sandbox resources owned by an orchestration Run. */
public interface SandboxLeaseRepository {

    int insert(NewSandboxLease lease);

    Optional<SandboxLease> findById(UUID leaseId, UUID orgId);

    Optional<SandboxLease> findByAttemptId(UUID attemptId, UUID orgId);

    Optional<SandboxLease> findLatestCheckpointBeforeAttempt(UUID attemptId, UUID orgId);

    int activate(
            UUID leaseId,
            UUID orgId,
            String providerSandboxId,
            String providerStateJson,
            OffsetDateTime heartbeatAt,
            OffsetDateTime leaseExpiresAt);

    int heartbeat(
            UUID leaseId, UUID orgId, OffsetDateTime heartbeatAt, OffsetDateTime leaseExpiresAt);

    int checkpoint(UUID leaseId, UUID orgId, String workspaceSnapshotUri, String workspaceVersion);

    int release(UUID leaseId, UUID orgId, OffsetDateTime releasedAt);

    int releaseAfterProvisioningFailure(
            UUID leaseId, UUID orgId, OffsetDateTime releasedAt, String error);

    record NewSandboxLease(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            String providerId,
            String imageOrTemplate,
            String capabilitiesJson,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime createdAt) {}

    record SandboxLease(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            String providerId,
            String providerSandboxId,
            String providerStateJson,
            String imageOrTemplate,
            String capabilitiesJson,
            String workspaceSnapshotUri,
            String workspaceVersion,
            String status,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime lastHeartbeatAt,
            OffsetDateTime createdAt,
            OffsetDateTime releasedAt,
            String releaseError) {}
}
