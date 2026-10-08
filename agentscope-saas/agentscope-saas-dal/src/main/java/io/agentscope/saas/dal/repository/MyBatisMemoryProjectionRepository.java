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
            int batchSize, OffsetDateTime now, OffsetDateTime legacyStaleBefore) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Memory projection batch must be in 1..1000");
        }
        return mapper.findReplayable(batchSize, now, legacyStaleBefore).stream()
                .map(MyBatisMemoryProjectionRepository::toDomain)
                .toList();
    }

    @Override
    public boolean claim(
            MemoryProjectionEvent event,
            UUID token,
            int maxAttempts,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            OffsetDateTime legacyStaleBefore) {
        if (token == null || maxAttempts < 1 || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("Invalid memory projection lease");
        }
        return mapper.claim(
                        event.orgId(),
                        event.id(),
                        token,
                        event.attempts(),
                        maxAttempts,
                        now,
                        leaseUntil,
                        legacyStaleBefore)
                == 1;
    }

    @Override
    public boolean markSynced(UUID orgId, UUID id, UUID token, OffsetDateTime syncedAt) {
        return mapper.markSynced(orgId, id, token, syncedAt) == 1;
    }

    @Override
    public boolean markFailed(
            UUID orgId,
            UUID id,
            UUID token,
            String error,
            int maxAttempts,
            OffsetDateTime failedAt,
            OffsetDateTime nextAttemptAt) {
        return mapper.markFailed(orgId, id, token, error, maxAttempts, failedAt, nextAttemptAt)
                == 1;
    }

    @Override
    public boolean exhaust(
            UUID orgId,
            UUID id,
            int maxAttempts,
            OffsetDateTime now,
            OffsetDateTime legacyStaleBefore) {
        return mapper.exhaust(orgId, id, maxAttempts, now, legacyStaleBefore) == 1;
    }

    private static MemoryProjectionEvent toDomain(MemoryProjectionData row) {
        return new MemoryProjectionEvent(
                row.id(),
                row.orgId(),
                row.userId(),
                row.agentId(),
                row.sessionId(),
                row.contentJson(),
                row.metadataJson(),
                row.attempts());
    }
}
