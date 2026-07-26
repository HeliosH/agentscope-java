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

/** Persistence port for the cross-tenant durable task worker state machine. */
public interface DurableTaskLeaseRepository {

    List<TaskCandidate> findReadyCandidates(OffsetDateTime readyAt, int limit);

    int claimTask(UUID taskId, OffsetDateTime claimedAt);

    void createAttempt(NewAttempt attempt);

    Optional<AttemptRef> findAttempt(UUID attemptId, String workerId);

    int startAttempt(
            UUID attemptId,
            String workerId,
            OffsetDateTime startedAt,
            OffsetDateTime leaseExpiresAt);

    int markTaskRunning(UUID taskId, OffsetDateTime updatedAt);

    int markAgentRunRunning(UUID agentRunId, OffsetDateTime updatedAt);

    int heartbeat(
            UUID attemptId,
            String workerId,
            OffsetDateTime heartbeatAt,
            OffsetDateTime leaseExpiresAt);

    List<ExpiredAttempt> findExpiredAttempts(OffsetDateTime expiredBefore, int limit);

    int finishAttempt(
            UUID attemptId,
            String workerId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime requireExpiredBefore);

    int completeTask(
            UUID taskId, OffsetDateTime completedAt, OffsetDateTime updatedAt, String outputJson);

    int releaseReadyDependencies(UUID runId, OffsetDateTime updatedAt);

    int completeRunIfAllTasksTerminal(UUID runId, OffsetDateTime completedAt);

    boolean isCoordinatorTask(UUID taskId);

    Optional<CoordinatorRef> findCompletedCoordinator(UUID runId);

    int scheduleCoordinatorContinuation(
            UUID taskId, String inputJson, int minimumMaxAttempts, OffsetDateTime updatedAt);

    int resetAgentRun(UUID agentRunId, OffsetDateTime updatedAt);

    int scheduleTaskRetry(
            UUID taskId,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime updatedAt,
            String errorCode,
            String errorMessage);

    int stopTask(
            UUID taskId,
            String status,
            OffsetDateTime completedAt,
            String errorCode,
            String errorMessage);

    int failRun(UUID runId, String errorCode, String errorMessage, OffsetDateTime completedAt);

    int cancelSiblingTasks(UUID runId, UUID taskId, OffsetDateTime completedAt);

    int cancelSiblingAttempts(UUID runId, UUID taskId, OffsetDateTime completedAt);

    int cancelSiblingAgentRuns(UUID runId, UUID taskId, OffsetDateTime completedAt);

    int updateAgentRun(
            UUID agentRunId, String status, OffsetDateTime updatedAt, OffsetDateTime completedAt);

    long nextEventSequence(UUID runId, OffsetDateTime updatedAt);

    void appendRunEvent(NewRunEvent event);

    void appendOutbox(NewOutboxEvent event);

    record TaskCandidate(
            UUID taskId,
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID agentRunId,
            String agentType,
            String subSessionId,
            String role,
            String tier,
            int maxSandboxes,
            long tokenQuota,
            String title,
            String inputJson,
            WorkspaceIsolationMode workspaceIsolationMode,
            int maxAttempts,
            String retryMode,
            int retryBaseSeconds,
            int lastAttemptNo) {}

    record AttemptRef(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            int maxAttempts,
            String retryMode,
            int retryBaseSeconds) {}

    record ExpiredAttempt(UUID attemptId, String workerId) {}

    record CoordinatorRef(UUID taskId, UUID agentRunId) {}

    record NewAttempt(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            String workerId,
            OffsetDateTime leaseExpiresAt,
            OffsetDateTime heartbeatAt,
            String idempotencyKey) {}

    record NewRunEvent(
            UUID eventId,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            long sequence,
            String eventType,
            String payloadJson) {}

    record NewOutboxEvent(
            UUID eventId, UUID orgId, UUID runId, String eventType, String payloadJson) {}
}
