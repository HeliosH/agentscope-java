/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.RunExecutionPolicyMapper;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicy;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicyRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRunExecutionPolicyRepository implements RunExecutionPolicyRepository {
    private final RunExecutionPolicyMapper mapper;

    public MyBatisRunExecutionPolicyRepository(RunExecutionPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(RunExecutionPolicy policy) {
        if (mapper.insert(policy) != 1) throw new IllegalStateException("RUN_POLICY_SCOPE_INVALID");
        if (mapper.markAccepted(policy) != 1)
            throw new IllegalStateException("RUN_POLICY_ALREADY_ACCEPTED");
    }

    @Override
    public Optional<RunExecutionPolicy> find(UUID orgId, UUID runId) {
        return mapper.find(orgId, runId).stream().findFirst();
    }

    @Override
    public boolean requiresSnapshot(UUID orgId, UUID runId) {
        return !mapper.requiredVersion(orgId, runId).isEmpty();
    }
}
