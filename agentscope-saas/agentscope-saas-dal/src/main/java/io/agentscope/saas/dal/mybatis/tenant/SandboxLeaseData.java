/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** MyBatis data object for one orchestration-owned sandbox lease. */
public record SandboxLeaseData(
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
