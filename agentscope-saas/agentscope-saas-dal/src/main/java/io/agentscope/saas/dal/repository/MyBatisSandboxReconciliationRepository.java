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

import io.agentscope.saas.dal.mybatis.admin.SandboxReconciliationMapper;
import io.agentscope.saas.dal.mybatis.admin.SandboxResourceData;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis implementation of cross-tenant sandbox reconciliation persistence. */
@Repository
public class MyBatisSandboxReconciliationRepository implements SandboxReconciliationRepository {

    private final SandboxReconciliationMapper mapper;

    public MyBatisSandboxReconciliationRepository(SandboxReconciliationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<SandboxResource> findExpiredActive(OffsetDateTime staleBefore, int limit) {
        return mapper.findExpiredActive(staleBefore, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SandboxResource> findBackendReleaseCandidates(int maxAttempts, int limit) {
        return mapper.findBackendReleaseCandidates(maxAttempts, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public int markExpiredActiveEvicted(UUID sandboxId, OffsetDateTime changedAt) {
        return mapper.markExpiredActiveEvicted(sandboxId, changedAt);
    }

    @Override
    public int claimBackendRelease(UUID sandboxId, int maxAttempts) {
        return mapper.claimBackendRelease(sandboxId, maxAttempts);
    }

    @Override
    public int recordBackendRelease(
            UUID sandboxId,
            String status,
            int attemptIncrement,
            OffsetDateTime releasedAt,
            String error) {
        return mapper.recordBackendRelease(sandboxId, status, attemptIncrement, releasedAt, error);
    }

    private SandboxResource toDomain(SandboxResourceData data) {
        return new SandboxResource(
                data.id(), data.orgId(), data.userId(), data.sandboxType(), data.externalId());
    }
}
