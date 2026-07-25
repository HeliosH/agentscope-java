/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.MemoryProjectionData;
import io.agentscope.saas.dal.mybatis.admin.MemoryProjectionMapper;
import io.agentscope.saas.domain.memory.MemoryProjectionEvent;
import io.agentscope.saas.domain.memory.MemoryProjectionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing durable memory projection persistence. */
@Repository
public class MyBatisMemoryProjectionRepository implements MemoryProjectionRepository {

    private final MemoryProjectionMapper mapper;

    public MyBatisMemoryProjectionRepository(MemoryProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MemoryProjectionEvent> findReplayable(
            int batchSize, int maxAttempts, OffsetDateTime staleBefore) {
        return mapper.findReplayable(batchSize, maxAttempts, staleBefore).stream()
                .map(MyBatisMemoryProjectionRepository::toDomain)
                .toList();
    }

    @Override
    public boolean claim(
            UUID id, int maxAttempts, OffsetDateTime staleBefore, OffsetDateTime claimedAt) {
        return mapper.claim(id, maxAttempts, staleBefore, claimedAt) == 1;
    }

    @Override
    public void markSynced(UUID id, OffsetDateTime syncedAt) {
        mapper.markSynced(id, syncedAt);
    }

    @Override
    public void markFailed(UUID id, String error, OffsetDateTime failedAt) {
        mapper.markFailed(id, error, failedAt);
    }

    private static MemoryProjectionEvent toDomain(MemoryProjectionData row) {
        return new MemoryProjectionEvent(
                row.id(),
                row.orgId(),
                row.userId(),
                row.agentId(),
                row.sessionId(),
                row.contentJson(),
                row.metadataJson());
    }
}
