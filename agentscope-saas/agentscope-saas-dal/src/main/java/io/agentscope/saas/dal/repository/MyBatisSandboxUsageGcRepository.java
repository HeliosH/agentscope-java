/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.SandboxUsageGcMapper;
import io.agentscope.saas.domain.sandbox.SandboxUsageGcRepository;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Resource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisSandboxUsageGcRepository implements SandboxUsageGcRepository {
    private final SandboxUsageGcMapper mapper;

    public MyBatisSandboxUsageGcRepository(SandboxUsageGcMapper mapper) {
        this.mapper = mapper;
    }

    public List<Resource> candidates(
            String deployment, OffsetDateTime stale, int attempts, int limit) {
        if (limit < 1 || limit > 500 || attempts < 1)
            throw new IllegalArgumentException("Invalid sandbox GC window");
        return mapper.candidates(deployment, stale, attempts, limit);
    }

    public Optional<Resource> claim(
            UUID id, UUID token, OffsetDateTime stale, OffsetDateTime until, int attempts) {
        if (mapper.lock(id).isEmpty()) return Optional.empty();
        // Re-read after obtaining the lock: a heartbeat may have committed while we waited.
        if (mapper.eligible(id, stale, attempts).isEmpty()) return Optional.empty();
        mapper.expire(id, stale);
        if (mapper.claim(id, token, until) != 1)
            throw new IllegalStateException("SANDBOX_RESOURCE_CLAIM_REJECTED");
        return mapper.find(id).stream().findFirst();
    }

    public boolean finish(UUID id, UUID token, boolean success) {
        return mapper.finish(id, token, success ? "RELEASED" : "RELEASE_FAILED") == 1;
    }
}
