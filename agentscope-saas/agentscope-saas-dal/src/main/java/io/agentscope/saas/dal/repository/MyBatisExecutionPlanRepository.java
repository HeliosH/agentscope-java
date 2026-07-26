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

import io.agentscope.saas.dal.mybatis.tenant.ExecutionPlanMapper;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the versioned execution-plan aggregate. */
@Repository
public class MyBatisExecutionPlanRepository implements ExecutionPlanRepository {

    private final ExecutionPlanMapper mapper;

    public MyBatisExecutionPlanRepository(ExecutionPlanMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredPlan> findLatest(UUID runId, UUID orgId) {
        return first(mapper.findLatest(runId, orgId));
    }

    @Override
    public Optional<StoredPlan> findByHash(UUID runId, UUID orgId, String planHash) {
        return first(mapper.findByHash(runId, orgId, planHash));
    }

    @Override
    public Optional<StoredPlan> findById(UUID planId, UUID runId, UUID orgId) {
        return first(mapper.findById(planId, runId, orgId));
    }

    @Override
    public void insertPlan(NewPlan plan) {
        requireOne(mapper.insertPlan(plan), "insert execution plan " + plan.id());
    }

    @Override
    public void insertPlanTask(PlanTaskLink link) {
        requireOne(mapper.insertPlanTask(link), "link execution plan task " + link.taskId());
    }

    @Override
    public void insertEdge(NewTaskEdge edge) {
        requireOne(mapper.insertEdge(edge), "insert task edge " + edge.id());
    }

    @Override
    public void insertApproval(NewApproval approval) {
        requireOne(mapper.insertApproval(approval), "insert Run approval " + approval.id());
    }

    @Override
    public Optional<Approval> findPendingApproval(UUID runId, UUID planId, UUID orgId) {
        return first(mapper.findPendingApproval(runId, planId, orgId));
    }

    @Override
    public Optional<Approval> findApprovalByIdempotencyKey(
            UUID runId, UUID orgId, String idempotencyKey) {
        return first(mapper.findApprovalByIdempotencyKey(runId, orgId, idempotencyKey));
    }

    @Override
    public int decideApproval(
            UUID approvalId,
            UUID orgId,
            String status,
            String decisionJson,
            UUID decidedBy,
            String idempotencyKey,
            OffsetDateTime decidedAt) {
        return mapper.decideApproval(
                approvalId, orgId, status, decisionJson, decidedBy, idempotencyKey, decidedAt);
    }

    @Override
    public int updatePlanStatus(UUID planId, UUID orgId, String status, OffsetDateTime decidedAt) {
        return mapper.updatePlanStatus(planId, orgId, status, decidedAt);
    }

    @Override
    public int supersedePlan(UUID planId, UUID orgId, OffsetDateTime updatedAt) {
        return mapper.supersedePlan(planId, orgId, updatedAt);
    }

    @Override
    public int updateRunPlanningState(
            UUID runId, UUID orgId, String mode, String status, OffsetDateTime updatedAt) {
        return mapper.updateRunPlanningState(runId, orgId, mode, status, updatedAt);
    }

    @Override
    public int settleCoordinatorForPlan(UUID runId, UUID orgId, OffsetDateTime completedAt) {
        int tasks = mapper.settleCoordinatorTask(runId, orgId, completedAt);
        mapper.settleCoordinatorAttempts(runId, orgId, completedAt);
        mapper.settleCoordinatorAgents(runId, orgId, completedAt);
        return tasks;
    }

    @Override
    public int cancelSupersededPlanTasks(
            UUID runId, UUID orgId, UUID retainedPlanId, OffsetDateTime completedAt) {
        return mapper.cancelSupersededPlanTasks(runId, orgId, retainedPlanId, completedAt);
    }

    @Override
    public int releaseRootPlanTasks(UUID planId, UUID runId, UUID orgId, OffsetDateTime updatedAt) {
        return mapper.releaseRootPlanTasks(planId, runId, orgId, updatedAt);
    }

    @Override
    public List<PlanTask> findPlanTasks(UUID planId, UUID runId, UUID orgId) {
        return mapper.findPlanTasks(planId, runId, orgId);
    }

    @Override
    public List<TaskEdge> findPlanEdges(UUID planId, UUID runId, UUID orgId) {
        return mapper.findPlanEdges(planId, runId, orgId);
    }

    private static <T> Optional<T> first(List<T> values) {
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private static void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(operation + " affected " + updated + " rows");
        }
    }
}
