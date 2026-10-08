/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.RuntimeBodyGcMapper;
import io.agentscope.saas.dal.mybatis.tenant.RuntimeBodyMapper;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRuntimeBodyRepository implements RuntimeBodyRepository {
    private final RuntimeBodyMapper tenant;
    private final RuntimeBodyGcMapper admin;

    public MyBatisRuntimeBodyRepository(RuntimeBodyMapper tenant, RuntimeBodyGcMapper admin) {
        this.tenant = tenant;
        this.admin = admin;
    }

    @Override
    public void stage(Body body) {
        if (tenant.stage(body) != 1)
            throw new IllegalStateException("Runtime body reservation failed");
    }

    @Override
    public int ready(Scope scope, UUID id, OffsetDateTime now, OffsetDateTime eligibleAt) {
        return tenant.ready(scope, id, now, eligibleAt);
    }

    @Override
    public int attach(Scope scope, UUID id, OffsetDateTime eligibleAt) {
        return tenant.attach(scope, id, eligibleAt);
    }

    @Override
    public Optional<Body> findOwned(Scope scope, UUID id) {
        return tenant.findOwned(scope, id).stream().findFirst();
    }

    @Override
    public List<Body> candidates(OffsetDateTime now, int attempts, int limit) {
        return admin.candidates(now, attempts, limit);
    }

    @Override
    public int claim(UUID id, UUID token, OffsetDateTime now, OffsetDateTime lease, int attempts) {
        return admin.claim(id, token, now, lease, attempts);
    }

    @Override
    public int collected(UUID id, UUID token, OffsetDateTime recheck) {
        return admin.collected(id, token, recheck);
    }

    @Override
    public int failed(UUID id, UUID token, OffsetDateTime retry) {
        return admin.failed(id, token, retry);
    }

    @Override
    public long references(UUID id) {
        return admin.references(id);
    }

    @Override
    public int retain(UUID id, UUID token, OffsetDateTime eligible) {
        return admin.retain(id, token, eligible);
    }

    @Override
    public boolean lockQuota(Scope scope) {
        return !tenant.lockQuota(scope).isEmpty();
    }

    @Override
    public long usage(UUID org, UUID user) {
        return tenant.usage(org, user);
    }
}
