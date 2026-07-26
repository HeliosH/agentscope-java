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

/** Tenant-scoped persistence port for the durable Run aggregate. */
public interface RunOrchestrationRepository {

    Optional<AssistantRun> findOwnedRun(UUID runId, UUID orgId, UUID userId, UUID agentId);

    Optional<AssistantRun> findByIdempotencyKey(
            UUID orgId, UUID userId, UUID agentId, String idempotencyKey);

    Optional<AssistantRun> findLatestOwnedRunBySession(
            UUID sessionId, UUID orgId, UUID userId, UUID agentId);

    Optional<AssistantRun> lockOwnedRun(UUID runId, UUID orgId, UUID userId, UUID agentId);

    void insertRun(NewRun run);

    void completeRun(
            UUID runId,
            UUID orgId,
            String status,
            boolean cancelRequested,
            String failureCode,
            String failureMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt);

    void touchRun(UUID runId, UUID orgId, OffsetDateTime updatedAt);

    void reopenRun(UUID runId, UUID orgId, OffsetDateTime updatedAt);

    void detachMessageReferencesForSession(UUID sessionId, UUID orgId);

    void deleteBySessionId(UUID sessionId, UUID orgId);

    List<TaskNode> findTasks(UUID runId, UUID orgId);

    Optional<TaskNode> findTask(UUID taskId, UUID runId, UUID orgId);

    Optional<TaskNode> findTaskByExternalId(UUID runId, UUID orgId, String externalTaskId);

    boolean hasUnsettledChildren(UUID runId);

    void insertTask(NewTask task);

    void assignTaskOwner(UUID taskId, UUID orgId, UUID ownerAgentRunId, OffsetDateTime updatedAt);

    void completeTask(
            UUID taskId,
            UUID orgId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt);

    void scheduleTaskContinuation(
            UUID taskId, UUID orgId, String inputJson, int maxAttempts, OffsetDateTime updatedAt);

    List<AgentRun> findAgentRuns(UUID runId, UUID orgId);

    void insertAgentRun(NewAgentRun agentRun);

    void updateAgentRunStatus(
            UUID agentRunId,
            UUID orgId,
            String status,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt);

    List<RunAttempt> findAttempts(UUID runId, UUID orgId);

    void insertAttempt(NewAttempt attempt);

    void updateAttemptStatus(
            UUID attemptId,
            UUID orgId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt);

    long nextEventSequence(UUID runId, UUID orgId, OffsetDateTime updatedAt);

    void insertEvent(NewEvent event);

    void insertOutbox(NewOutboxMessage message);

    List<RunEvent> findEvents(UUID runId, UUID orgId, UUID userId, long afterSequence, int limit);

    record AssistantRun(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            String mode,
            String status,
            boolean cancelRequested,
            String failureCode,
            String failureMessage,
            Long tokenBudget,
            long consumedTokens,
            Long costBudgetMicros,
            long consumedCostMicros,
            Integer modelCallBudget,
            int consumedModelCalls,
            OffsetDateTime deadlineAt,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {}

    record NewRun(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            String idempotencyKey,
            String mode,
            String status,
            Long tokenBudget,
            Long costBudgetMicros,
            Integer modelCallBudget,
            OffsetDateTime deadlineAt,
            OffsetDateTime startedAt,
            OffsetDateTime updatedAt) {}

    record TaskNode(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID parentId,
            UUID ownerAgentRunId,
            String externalTaskId,
            String title,
            String taskType,
            String status,
            String inputJson,
            String workspaceMode,
            int maxAttempts,
            Long tokenBudget,
            long consumedTokens,
            Long costBudgetMicros,
            long consumedCostMicros,
            Integer modelCallBudget,
            int consumedModelCalls,
            OffsetDateTime deadlineAt,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {}

    record NewTask(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID parentId,
            String externalTaskId,
            String subSessionId,
            String title,
            String taskType,
            String status,
            int priority,
            String inputJson,
            String expectedOutputJson,
            String outputJson,
            String acceptanceJson,
            String workspaceMode,
            int maxAttempts,
            String retryMode,
            int retryBaseSeconds,
            Long tokenBudget,
            Long costBudgetMicros,
            Integer modelCallBudget,
            OffsetDateTime deadlineAt,
            OffsetDateTime updatedAt) {}

    record AgentRun(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID parentAgentRunId,
            String agentType,
            String status,
            int depth,
            String permissionSnapshotJson,
            String permissionSnapshotHash) {}

    record NewAgentRun(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID parentAgentRunId,
            String agentType,
            String status,
            int depth,
            String contextPolicy,
            String permissionSnapshotJson,
            String permissionSnapshotHash,
            OffsetDateTime updatedAt) {}

    record RunAttempt(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {}

    record NewAttempt(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            String status,
            String idempotencyKey,
            OffsetDateTime startedAt,
            OffsetDateTime updatedAt) {}

    record RunEvent(
            long sequence,
            String eventType,
            UUID taskId,
            String payloadJson,
            OffsetDateTime createdAt) {}

    record NewEvent(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            long sequence,
            String eventType,
            String payloadJson) {}

    record NewOutboxMessage(
            UUID id,
            UUID orgId,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payloadJson) {}
}
