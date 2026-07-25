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

import io.agentscope.saas.dal.mybatis.admin.OrchestrationBudgetData;
import io.agentscope.saas.dal.mybatis.admin.OrchestrationGovernanceMapper;
import io.agentscope.saas.domain.orchestration.OrchestrationBudget;
import io.agentscope.saas.domain.orchestration.OrchestrationGovernanceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing orchestration governance persistence. */
@Repository
public class MyBatisOrchestrationGovernanceRepository implements OrchestrationGovernanceRepository {

    private final OrchestrationGovernanceMapper mapper;

    public MyBatisOrchestrationGovernanceRepository(OrchestrationGovernanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OffsetDateTime> findEffectiveDeadline(UUID orgId, UUID runId, UUID agentRunId) {
        List<OffsetDateTime> rows = mapper.findEffectiveDeadline(orgId, runId, agentRunId);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<BudgetScope> findExpiredScopes(int limit) {
        return mapper.findExpiredScopes(limit).stream()
                .map(row -> new BudgetScope(row.orgId(), row.runId(), row.agentRunId()))
                .toList();
    }

    @Override
    public Optional<PermissionSnapshot> findPermissionSnapshot(
            UUID orgId, UUID runId, UUID agentRunId) {
        return mapper.findPermissionSnapshot(orgId, runId, agentRunId).stream()
                .findFirst()
                .map(row -> new PermissionSnapshot(row.json(), row.hash()));
    }

    @Override
    public OrchestrationBudget lockBudget(UUID orgId, UUID runId, UUID agentRunId) {
        return mapper.lockBudget(orgId, runId, agentRunId).stream()
                .findFirst()
                .map(MyBatisOrchestrationGovernanceRepository::toDomain)
                .orElseThrow(() -> new IllegalStateException("Run budget scope was not found"));
    }

    @Override
    public void recordUsage(
            OrchestrationBudget budget,
            OffsetDateTime now,
            long tokenDelta,
            long costDelta,
            int modelCallDelta) {
        mapper.recordRunUsage(
                budget.runId(), budget.orgId(), now, tokenDelta, costDelta, modelCallDelta);
        mapper.recordTaskUsage(
                budget.taskId(), budget.orgId(), now, tokenDelta, costDelta, modelCallDelta);
    }

    @Override
    public boolean failRun(
            OrchestrationBudget budget, OffsetDateTime now, String reason, String message) {
        return mapper.failRun(budget.runId(), budget.orgId(), now, reason, message) == 1;
    }

    @Override
    public void failOutstandingWork(
            OrchestrationBudget budget, OffsetDateTime now, String reason, String message) {
        mapper.failTasks(budget.runId(), budget.taskId(), now, reason, message);
        mapper.failAgentRuns(budget.runId(), budget.taskId(), now);
        mapper.failAttempts(budget.runId(), budget.taskId(), now, reason, message);
    }

    @Override
    public long nextEventSequence(UUID runId, OffsetDateTime now) {
        if (mapper.incrementEventSequence(runId, now) != 1) {
            throw new IllegalStateException(
                    "Run disappeared while appending governance event: " + runId);
        }
        Long sequence = mapper.findEventSequence(runId);
        if (sequence == null) {
            throw new IllegalStateException("Run event sequence was not found: " + runId);
        }
        return sequence;
    }

    @Override
    public void appendBudgetExceededEvent(
            UUID eventId, OrchestrationBudget budget, long sequence, String payloadJson) {
        mapper.insertBudgetExceededEvent(
                eventId, budget.orgId(), budget.runId(), budget.taskId(), sequence, payloadJson);
    }

    @Override
    public void appendBudgetExceededOutbox(
            UUID outboxId, OrchestrationBudget budget, String envelopeJson) {
        mapper.insertBudgetExceededOutbox(outboxId, budget.orgId(), budget.runId(), envelopeJson);
    }

    private static OrchestrationBudget toDomain(OrchestrationBudgetData row) {
        return new OrchestrationBudget(
                row.runId(),
                row.orgId(),
                row.runStatus(),
                row.runTokenBudget(),
                row.runConsumedTokens(),
                row.runCostBudget(),
                row.runConsumedCost(),
                row.runCallBudget(),
                row.runConsumedCalls(),
                row.runDeadline(),
                row.taskId(),
                row.taskTokenBudget(),
                row.taskConsumedTokens(),
                row.taskCostBudget(),
                row.taskConsumedCost(),
                row.taskCallBudget(),
                row.taskConsumedCalls(),
                row.taskDeadline());
    }
}
