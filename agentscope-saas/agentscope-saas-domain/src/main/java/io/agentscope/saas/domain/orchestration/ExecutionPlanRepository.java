/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence port for versioned plans and approval decisions. */
public interface ExecutionPlanRepository {

    Optional<StoredPlan> findLatest(UUID runId, UUID orgId);

    Optional<StoredPlan> findByHash(UUID runId, UUID orgId, String planHash);

    Optional<StoredPlan> findById(UUID planId, UUID runId, UUID orgId);

    void insertPlan(NewPlan plan);

    void insertPlanTask(PlanTaskLink link);

    void insertEdge(NewTaskEdge edge);

    void insertApproval(NewApproval approval);

    Optional<Approval> findPendingApproval(UUID runId, UUID planId, UUID orgId);

    Optional<Approval> findApprovalByIdempotencyKey(UUID runId, UUID orgId, String idempotencyKey);

    int decideApproval(
            UUID approvalId,
            UUID orgId,
            String status,
            String decisionJson,
            UUID decidedBy,
            String idempotencyKey,
            OffsetDateTime decidedAt);

    int updatePlanStatus(UUID planId, UUID orgId, String status, OffsetDateTime decidedAt);

    int supersedePlan(UUID planId, UUID orgId, OffsetDateTime updatedAt);

    int updateRunPlanningState(
            UUID runId, UUID orgId, String mode, String status, OffsetDateTime updatedAt);

    int settleCoordinatorForPlan(UUID runId, UUID orgId, OffsetDateTime completedAt);

    int cancelSupersededPlanTasks(
            UUID runId, UUID orgId, UUID retainedPlanId, OffsetDateTime completedAt);

    int releaseRootPlanTasks(UUID planId, UUID runId, UUID orgId, OffsetDateTime updatedAt);

    List<PlanTask> findPlanTasks(UUID planId, UUID runId, UUID orgId);

    List<TaskEdge> findPlanEdges(UUID planId, UUID runId, UUID orgId);

    record StoredPlan(
            UUID id,
            UUID orgId,
            UUID runId,
            int version,
            String status,
            String goal,
            String planJson,
            String planHash,
            UUID supersedesPlanId,
            boolean approvalRequired,
            OffsetDateTime createdAt,
            OffsetDateTime decidedAt) {}

    record NewPlan(
            UUID id,
            UUID orgId,
            UUID runId,
            int version,
            String status,
            String goal,
            String planJson,
            String planHash,
            UUID supersedesPlanId,
            boolean approvalRequired,
            UUID createdBy,
            OffsetDateTime createdAt) {}

    record PlanTaskLink(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID planId,
            UUID taskId,
            String clientTaskId,
            String taskSpecHash) {}

    record NewTaskEdge(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID planId,
            UUID fromTaskId,
            UUID toTaskId,
            String edgeType) {}

    record NewApproval(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID planId,
            String approvalType,
            String status,
            String requestJson,
            OffsetDateTime requestedAt) {}

    record Approval(
            UUID id,
            UUID planId,
            String status,
            String requestJson,
            String decisionJson,
            String idempotencyKey,
            OffsetDateTime requestedAt,
            OffsetDateTime decidedAt,
            UUID decidedBy) {}

    record PlanTask(
            UUID taskId,
            String clientTaskId,
            String title,
            String agentType,
            String status,
            String workspaceMode,
            String acceptanceJson,
            UUID ownerAgentRunId,
            String taskSpecHash) {}

    record TaskEdge(UUID fromTaskId, UUID toTaskId, String edgeType) {}
}
