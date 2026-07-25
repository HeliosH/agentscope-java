/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.SandboxEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for managed sandbox resources. */
public interface SandboxRepository {

    int countByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    List<SandboxEntity> findByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    List<SandboxEntity> findAdminSandboxes(
            UUID orgId,
            UUID userId,
            String status,
            String sandboxType,
            boolean expiredOnly,
            OffsetDateTime now,
            int limit);

    Optional<SandboxEntity> findById(UUID id);

    SandboxEntity save(SandboxEntity sandbox);
}
