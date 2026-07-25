/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.MemoryEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for the durable memory event ledger. */
public interface MemoryEventRepository {

    List<MemoryEventEntity> findTop100BySyncStatusOrderByCreatedAtAsc(String syncStatus);

    List<MemoryEventEntity> findByOrgIdAndUserIdOrderByCreatedAtDesc(UUID orgId, UUID userId);

    List<MemoryEventEntity> findAdminEvents(
            UUID orgId, UUID userId, String sessionId, String syncStatus, int limit);

    Optional<MemoryEventEntity> findById(UUID id);

    MemoryEventEntity save(MemoryEventEntity event);
}
