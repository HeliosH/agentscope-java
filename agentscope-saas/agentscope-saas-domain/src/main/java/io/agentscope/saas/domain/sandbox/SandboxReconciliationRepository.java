/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.sandbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Persistence port for cross-tenant sandbox resource reconciliation. */
public interface SandboxReconciliationRepository {

    List<SandboxPoolCount> countByTypeAndStatus();

    List<SandboxTypeCount> countExpiredActiveByType(OffsetDateTime now);

    List<SandboxResource> findExpiredActive(OffsetDateTime staleBefore, int limit);

    List<SandboxResource> findBackendReleaseCandidates(int maxAttempts, int limit);

    int markExpiredActiveEvicted(UUID sandboxId, OffsetDateTime changedAt);

    int claimBackendRelease(UUID sandboxId, int maxAttempts);

    int recordBackendRelease(
            UUID sandboxId,
            String status,
            int attemptIncrement,
            OffsetDateTime releasedAt,
            String error);

    record SandboxResource(
            UUID id, UUID orgId, UUID userId, String sandboxType, String externalId) {}

    record SandboxPoolCount(String sandboxType, String status, long count) {}

    record SandboxTypeCount(String sandboxType, long count) {}
}
