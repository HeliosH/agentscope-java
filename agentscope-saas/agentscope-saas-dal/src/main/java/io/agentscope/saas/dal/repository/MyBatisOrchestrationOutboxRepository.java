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

import io.agentscope.saas.dal.mybatis.admin.OrchestrationOutboxData;
import io.agentscope.saas.dal.mybatis.admin.OrchestrationOutboxMapper;
import io.agentscope.saas.domain.orchestration.OrchestrationOutboxMessage;
import io.agentscope.saas.domain.orchestration.OrchestrationOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing orchestration outbox persistence. */
@Repository
public class MyBatisOrchestrationOutboxRepository implements OrchestrationOutboxRepository {

    private final OrchestrationOutboxMapper mapper;

    public MyBatisOrchestrationOutboxRepository(OrchestrationOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<OrchestrationOutboxMessage> findClaimable(
            OffsetDateTime now, int batchSize, int maxAttempts) {
        return mapper.findClaimable(now, batchSize, maxAttempts).stream()
                .map(MyBatisOrchestrationOutboxRepository::toDomain)
                .toList();
    }

    @Override
    public boolean claim(
            UUID id,
            String workerId,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt,
            int maxAttempts) {
        return mapper.claim(id, workerId, now, leaseExpiresAt, maxAttempts) == 1;
    }

    @Override
    public void markPublished(UUID id, String workerId, OffsetDateTime publishedAt) {
        mapper.markPublished(id, workerId, publishedAt);
    }

    @Override
    public void markFailed(
            UUID id,
            String workerId,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime deadLetteredAt,
            String error) {
        mapper.markFailed(id, workerId, nextAttemptAt, deadLetteredAt, error);
    }

    private static OrchestrationOutboxMessage toDomain(OrchestrationOutboxData row) {
        return new OrchestrationOutboxMessage(
                row.id(),
                row.orgId(),
                row.aggregateId(),
                row.aggregateType(),
                row.eventType(),
                row.payloadJson(),
                row.createdAt(),
                row.attempts());
    }
}
