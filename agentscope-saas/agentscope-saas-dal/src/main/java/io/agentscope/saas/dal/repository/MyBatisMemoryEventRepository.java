/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.MemoryLedgerMapper;
import io.agentscope.saas.domain.model.MemoryEventEntity;
import io.agentscope.saas.domain.repository.MemoryEventRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the durable memory event ledger. */
@Repository
public class MyBatisMemoryEventRepository implements MemoryEventRepository {

    private final MemoryLedgerMapper mapper;

    public MyBatisMemoryEventRepository(MemoryLedgerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MemoryEventEntity> findTop100BySyncStatusOrderByCreatedAtAsc(String syncStatus) {
        return mapper.findBySyncStatus(syncStatus);
    }

    @Override
    public List<MemoryEventEntity> findByOrgIdAndUserIdOrderByCreatedAtDesc(
            UUID orgId, UUID userId) {
        return mapper.findUserEvents(orgId, userId);
    }

    @Override
    public List<MemoryEventEntity> findAdminEvents(
            UUID orgId, UUID userId, String sessionId, String syncStatus, int limit) {
        return mapper.findAdminEvents(orgId, userId, sessionId, syncStatus, limit);
    }

    @Override
    public Optional<MemoryEventEntity> findById(UUID id) {
        List<MemoryEventEntity> rows = mapper.findById(id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public MemoryEventEntity save(MemoryEventEntity event) {
        if (mapper.update(event) == 0) {
            int rows = mapper.insert(event);
            if (rows != 1) {
                throw new IllegalStateException(
                        "insert MemoryEvent " + event.getId() + " affected " + rows + " rows");
            }
        }
        return event;
    }
}
