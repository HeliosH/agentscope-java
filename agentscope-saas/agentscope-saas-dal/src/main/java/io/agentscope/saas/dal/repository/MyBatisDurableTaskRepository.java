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

import io.agentscope.saas.dal.mybatis.tenant.DurableTaskData;
import io.agentscope.saas.dal.mybatis.tenant.DurableTaskMapper;
import io.agentscope.saas.domain.orchestration.DurableTask;
import io.agentscope.saas.domain.orchestration.DurableTaskRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing the durable task domain persistence port. */
@Repository
public class MyBatisDurableTaskRepository implements DurableTaskRepository {

    private final DurableTaskMapper mapper;

    public MyBatisDurableTaskRepository(DurableTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<DurableTask> findLatest(TaskScope scope, String externalTaskId) {
        return mapper
                .findLatest(scope.orgId(), scope.userId(), scope.sessionId(), externalTaskId)
                .stream()
                .findFirst()
                .map(MyBatisDurableTaskRepository::toDomain);
    }

    @Override
    public List<DurableTask> findAll(TaskScope scope) {
        return mapper.findAll(scope.orgId(), scope.userId(), scope.sessionId()).stream()
                .map(MyBatisDurableTaskRepository::toDomain)
                .toList();
    }

    @Override
    public List<DurableTask> findPendingDeliveries(TaskScope scope) {
        return mapper
                .findPendingDeliveries(scope.orgId(), scope.userId(), scope.sessionId())
                .stream()
                .map(MyBatisDurableTaskRepository::toDomain)
                .toList();
    }

    @Override
    public void markDelivered(TaskScope scope, String externalTaskId, OffsetDateTime deliveredAt) {
        mapper.markDelivered(
                scope.orgId(), scope.userId(), scope.sessionId(), externalTaskId, deliveredAt);
    }

    private static DurableTask toDomain(DurableTaskData row) {
        return new DurableTask(
                row.id(),
                row.runId(),
                row.agentId(),
                row.externalTaskId(),
                row.agentType(),
                row.status(),
                row.outputJson(),
                row.errorMessage(),
                row.createdAt(),
                row.completedAt(),
                row.deliveredAt());
    }
}
