/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.ToolReconciliationExecutionMapper;
import io.agentscope.saas.dal.mybatis.tenant.ToolReconciliationMapper;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisToolReconciliationRepository implements ToolReconciliationRepository {
    private final ToolReconciliationMapper tenant;
    private final ToolReconciliationExecutionMapper execution;

    public MyBatisToolReconciliationRepository(
            ToolReconciliationMapper tenant, ToolReconciliationExecutionMapper execution) {
        this.tenant = tenant;
        this.execution = execution;
    }

    public Optional<RunScope> run(UUID org, UUID run) {
        return Optional.ofNullable(tenant.run(org, run));
    }

    public Optional<Operation> lockOperation(UUID org, UUID run, UUID operation) {
        return Optional.ofNullable(tenant.lockOperation(org, run, operation));
    }

    public List<Operation> operations(UUID org, UUID run, UUID after, int limit) {
        return tenant.operations(org, run, after, limit);
    }

    public List<Operation> organizationOperations(UUID org, String state, UUID after, int limit) {
        return tenant.organizationOperations(org, state, after, limit);
    }

    public Optional<Resolution> command(UUID org, UUID actor, String key) {
        return Optional.ofNullable(tenant.command(org, actor, key));
    }

    public Optional<Resolution> latest(UUID org, UUID run, UUID operation) {
        return Optional.ofNullable(tenant.latest(org, run, operation));
    }

    public List<Resolution> history(UUID org, UUID run, UUID operation, long after, int limit) {
        return tenant.history(org, run, operation, after, limit);
    }

    public void append(Resolution resolution) {
        tenant.append(resolution);
    }

    public void approve(ReplayPermit permit) {
        tenant.approve(permit);
    }

    public Optional<Resolution> executionResolution(UUID org, UUID run, UUID operation) {
        return Optional.ofNullable(execution.latest(org, run, operation));
    }

    public boolean consumeReplay(
            UUID org,
            UUID run,
            UUID operation,
            long version,
            UUID resolution,
            UUID invocation,
            String toolName,
            String toolCall,
            String inputHash,
            OffsetDateTime now) {
        return execution.consume(
                        org,
                        run,
                        operation,
                        version,
                        resolution,
                        invocation,
                        toolName,
                        toolCall,
                        inputHash,
                        now)
                == 1;
    }

    public ClaimAuthority claimAuthority(
            UUID org,
            UUID run,
            UUID operation,
            long version,
            UUID invocation,
            String toolName,
            String toolCall,
            String inputHash,
            OffsetDateTime now) {
        return ClaimAuthority.valueOf(
                execution.claimAuthority(
                        org,
                        run,
                        operation,
                        version,
                        invocation,
                        toolName,
                        toolCall,
                        inputHash,
                        now));
    }
}
