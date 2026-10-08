/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.ModelInvocationMapper;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisModelInvocationRepository implements ModelInvocationRepository {
    private final ModelInvocationMapper mapper;

    public MyBatisModelInvocationRepository(ModelInvocationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean lockOwner(Scope scope, boolean requireActiveOrganization) {
        return !mapper.lockOrg(scope.orgId(), requireActiveOrganization).isEmpty()
                && !mapper.lockUser(scope).isEmpty();
    }

    @Override
    public boolean activeLease(Scope scope, OffsetDateTime now) {
        return !mapper.activeOrganization(scope.orgId()).isEmpty()
                && (scope.runId() == null || !mapper.activeLease(scope, now).isEmpty());
    }

    @Override
    public Totals dailyUsage(
            Scope scope, boolean userOnly, OffsetDateTime since, OffsetDateTime now) {
        return mapper.dailyUsage(scope, userOnly, since, now);
    }

    @Override
    public Totals runReservations(Scope scope, boolean taskOnly, OffsetDateTime now) {
        return mapper.runReservations(scope, taskOnly, now);
    }

    @Override
    public void insert(NewInvocation invocation) {
        if (mapper.insert(invocation) != 1)
            throw new IllegalStateException("Invocation admission was not persisted");
    }

    @Override
    public Optional<Receipt> find(UUID orgId, UUID id) {
        return mapper.find(orgId, id).stream()
                .findFirst()
                .map(
                        row ->
                                new Receipt(
                                        row.id(),
                                        new Scope(
                                                row.orgId(),
                                                row.userId(),
                                                row.runId(),
                                                row.taskId(),
                                                row.agentRunId(),
                                                row.attemptId(),
                                                row.leaseOwner()),
                                        row.purpose(),
                                        row.modelId(),
                                        row.routeVersion(),
                                        row.status(),
                                        row.reservedTokens(),
                                        row.reservedCostMicros(),
                                        row.estimatedInputTokens(),
                                        row.inputTokens(),
                                        row.outputTokens(),
                                        row.totalTokens(),
                                        row.usageSource(),
                                        row.deadlineAt(),
                                        row.startedAt(),
                                        row.finishedAt(),
                                        row.reasonCode()));
    }

    @Override
    public List<InvocationReference> findExpired(OffsetDateTime before, int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid reconciliation batch size");
        return mapper.findExpired(before, limit);
    }

    @Override
    public boolean settle(Settlement settlement) {
        return mapper.settle(settlement) == 1;
    }

    @Override
    public void recordMetric(Scope scope, String metric, long value, String modelId) {
        mapper.recordMetric(
                scope, metric, value, modelId.length() > 64 ? modelId.substring(0, 64) : modelId);
    }

    @Override
    public Optional<PurposePolicy> policy(UUID orgId, String purpose) {
        return mapper.policy(orgId, purpose).stream().findFirst();
    }

    @Override
    public void savePolicy(PurposePolicy policy) {
        if (mapper.updatePolicy(policy) == 0) mapper.insertPolicy(policy);
    }
}
