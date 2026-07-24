/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.core.persistence.repo;

import io.agentscope.saas.core.persistence.entity.AssistantRunEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped access to durable assistant Runs. */
public interface AssistantRunRepository extends JpaRepository<AssistantRunEntity, UUID> {

    Optional<AssistantRunEntity> findByIdAndOrgIdAndUserIdAndAgentId(
            UUID id, UUID orgId, UUID userId, UUID agentId);

    Optional<AssistantRunEntity> findByOrgIdAndUserIdAndAgentIdAndIdempotencyKey(
            UUID orgId, UUID userId, UUID agentId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT r FROM AssistantRunEntity r WHERE r.id = :id AND r.orgId = :orgId AND"
                    + " r.userId = :userId AND r.agentId = :agentId")
    Optional<AssistantRunEntity> lockOwnedRun(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);
}
