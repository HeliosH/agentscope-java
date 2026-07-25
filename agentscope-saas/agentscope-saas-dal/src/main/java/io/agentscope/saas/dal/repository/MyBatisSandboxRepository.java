/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.SandboxLifecycleMapper;
import io.agentscope.saas.domain.model.SandboxEntity;
import io.agentscope.saas.domain.repository.SandboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant-scoped sandbox lifecycle operations. */
@Repository
public class MyBatisSandboxRepository implements SandboxRepository {

    private final SandboxLifecycleMapper mapper;

    public MyBatisSandboxRepository(SandboxLifecycleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int countByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status) {
        return mapper.countOwnedByStatus(orgId, userId, status);
    }

    @Override
    public List<SandboxEntity> findByOrgIdAndUserIdAndStatus(
            UUID orgId, UUID userId, String status) {
        return mapper.findOwnedByStatus(orgId, userId, status);
    }

    @Override
    public List<SandboxEntity> findAdminSandboxes(
            UUID orgId,
            UUID userId,
            String status,
            String sandboxType,
            boolean expiredOnly,
            OffsetDateTime now,
            int limit) {
        return mapper.findAdminSandboxes(
                orgId, userId, status, sandboxType, expiredOnly, now, limit);
    }

    @Override
    public Optional<SandboxEntity> findById(UUID id) {
        List<SandboxEntity> rows = mapper.findById(id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public SandboxEntity save(SandboxEntity sandbox) {
        if (mapper.update(sandbox) == 0) {
            int rows = mapper.insert(sandbox);
            if (rows != 1) {
                throw new IllegalStateException(
                        "insert Sandbox " + sandbox.getId() + " affected " + rows + " rows");
            }
        }
        return sandbox;
    }
}
