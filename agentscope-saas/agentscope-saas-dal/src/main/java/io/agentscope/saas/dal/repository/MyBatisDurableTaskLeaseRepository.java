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

import io.agentscope.saas.dal.mybatis.admin.DurableTaskLeaseMapper;
import io.agentscope.saas.dal.mybatis.admin.TaskLeaseAttemptData;
import io.agentscope.saas.dal.mybatis.admin.TaskLeaseCandidateData;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis implementation of the cross-tenant durable task worker persistence port. */
@Repository
public class MyBatisDurableTaskLeaseRepository implements DurableTaskLeaseRepository {

    private final DurableTaskLeaseMapper mapper;

    public MyBatisDurableTaskLeaseRepository(DurableTaskLeaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<TaskCandidate> findReadyCandidates(OffsetDateTime readyAt, int limit) {
        return mapper.findReadyCandidates(readyAt, limit).stream()
                .map(MyBatisDurableTaskLeaseRepository::toDomain)
                .toList();
    }

    @Override
    public int claimTask(UUID taskId, OffsetDateTime claimedAt) {
        return mapper.claimTask(taskId, claimedAt);
    }

    @Override
    public void createAttempt(NewAttempt attempt) {
        int inserted =
                mapper.createAttempt(
                        attempt.attemptId(),
                        attempt.orgId(),
                        attempt.runId(),
                        attempt.taskId(),
                        attempt.agentRunId(),
                        attempt.attemptNo(),
                        attempt.workerId(),
                        attempt.leaseExpiresAt(),
                        attempt.heartbeatAt(),
                        attempt.idempotencyKey());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Durable task attempt was not inserted: " + attempt.attemptId());
        }
    }

    @Override
    public Optional<AttemptRef> findAttempt(UUID attemptId, String workerId) {
        return mapper.findAttempt(attemptId, workerId).stream().findFirst().map(this::toDomain);
    }

    @Override
    public int startAttempt(
            UUID attemptId,
            String workerId,
            OffsetDateTime startedAt,
            OffsetDateTime leaseExpiresAt) {
        return mapper.startAttempt(attemptId, workerId, startedAt, leaseExpiresAt);
    }

    @Override
    public int markTaskRunning(UUID taskId, OffsetDateTime updatedAt) {
        return mapper.markTaskRunning(taskId, updatedAt);
    }

    @Override
    public int markAgentRunRunning(UUID agentRunId, OffsetDateTime updatedAt) {
        return mapper.markAgentRunRunning(agentRunId, updatedAt);
    }

    @Override
    public int heartbeat(
            UUID attemptId,
            String workerId,
            OffsetDateTime heartbeatAt,
            OffsetDateTime leaseExpiresAt) {
        return mapper.heartbeat(attemptId, workerId, heartbeatAt, leaseExpiresAt);
    }

    @Override
    public List<ExpiredAttempt> findExpiredAttempts(OffsetDateTime expiredBefore, int limit) {
        return mapper.findExpiredAttempts(expiredBefore, limit).stream()
                .map(data -> new ExpiredAttempt(data.attemptId(), data.workerId()))
                .toList();
    }

    @Override
    public int finishAttempt(
            UUID attemptId,
            String workerId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime requireExpiredBefore) {
        return mapper.finishAttempt(
                attemptId,
                workerId,
                status,
                errorCode,
                errorMessage,
                completedAt,
                requireExpiredBefore);
    }

    @Override
    public int completeTask(
            UUID taskId, OffsetDateTime completedAt, OffsetDateTime updatedAt, String outputJson) {
        return mapper.completeTask(taskId, completedAt, updatedAt, outputJson);
    }

    @Override
    public int releaseReadyDependencies(UUID runId, OffsetDateTime updatedAt) {
        return mapper.releaseReadyDependencies(runId, updatedAt);
    }

    @Override
    public int completeRunIfAllTasksTerminal(UUID runId, OffsetDateTime completedAt) {
        return mapper.completeRunIfAllTasksTerminal(runId, completedAt);
    }

    @Override
    public boolean isCoordinatorTask(UUID taskId) {
        return mapper.countCoordinatorTasks(taskId) == 1;
    }

    @Override
    public Optional<CoordinatorRef> findCompletedCoordinator(UUID runId) {
        return mapper.findCompletedCoordinator(runId).stream()
                .findFirst()
                .map(data -> new CoordinatorRef(data.taskId(), data.agentRunId()));
    }

    @Override
    public int scheduleCoordinatorContinuation(
            UUID taskId, String inputJson, int minimumMaxAttempts, OffsetDateTime updatedAt) {
        return mapper.scheduleCoordinatorContinuation(
                taskId, inputJson, minimumMaxAttempts, updatedAt);
    }

    @Override
    public int resetAgentRun(UUID agentRunId, OffsetDateTime updatedAt) {
        return mapper.resetAgentRun(agentRunId, updatedAt);
    }

    @Override
    public int scheduleTaskRetry(
            UUID taskId,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime updatedAt,
            String errorCode,
            String errorMessage) {
        return mapper.scheduleTaskRetry(taskId, nextAttemptAt, updatedAt, errorCode, errorMessage);
    }

    @Override
    public int stopTask(
            UUID taskId,
            String status,
            OffsetDateTime completedAt,
            String errorCode,
            String errorMessage) {
        return mapper.stopTask(taskId, status, completedAt, errorCode, errorMessage);
    }

    @Override
    public int failRun(
            UUID runId, String errorCode, String errorMessage, OffsetDateTime completedAt) {
        return mapper.failRun(runId, errorCode, errorMessage, completedAt);
    }

    @Override
    public int cancelSiblingTasks(UUID runId, UUID taskId, OffsetDateTime completedAt) {
        return mapper.cancelSiblingTasks(runId, taskId, completedAt);
    }

    @Override
    public int cancelSiblingAttempts(UUID runId, UUID taskId, OffsetDateTime completedAt) {
        return mapper.cancelSiblingAttempts(runId, taskId, completedAt);
    }

    @Override
    public int cancelSiblingAgentRuns(UUID runId, UUID taskId, OffsetDateTime completedAt) {
        return mapper.cancelSiblingAgentRuns(runId, taskId, completedAt);
    }

    @Override
    public int updateAgentRun(
            UUID agentRunId, String status, OffsetDateTime updatedAt, OffsetDateTime completedAt) {
        return mapper.updateAgentRun(agentRunId, status, updatedAt, completedAt);
    }

    @Override
    public long nextEventSequence(UUID runId, OffsetDateTime updatedAt) {
        if (mapper.incrementEventSequence(runId, updatedAt) != 1) {
            throw new IllegalStateException("Run disappeared while appending event: " + runId);
        }
        Long sequence = mapper.findEventSequence(runId);
        if (sequence == null) {
            throw new IllegalStateException(
                    "Run event sequence disappeared after increment: " + runId);
        }
        return sequence;
    }

    @Override
    public void appendRunEvent(NewRunEvent event) {
        int inserted =
                mapper.appendRunEvent(
                        event.eventId(),
                        event.orgId(),
                        event.runId(),
                        event.taskId(),
                        event.agentRunId(),
                        event.attemptId(),
                        event.sequence(),
                        event.eventType(),
                        event.payloadJson());
        if (inserted != 1) {
            throw new IllegalStateException("Run event was not inserted: " + event.eventId());
        }
    }

    @Override
    public void appendOutbox(NewOutboxEvent event) {
        int inserted =
                mapper.appendOutbox(
                        event.eventId(),
                        event.orgId(),
                        event.runId(),
                        event.eventType(),
                        event.payloadJson());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Orchestration outbox event was not inserted: " + event.eventId());
        }
    }

    private static TaskCandidate toDomain(TaskLeaseCandidateData data) {
        return new TaskCandidate(
                data.taskId(),
                data.orgId(),
                data.runId(),
                data.userId(),
                data.agentId(),
                data.sessionId(),
                data.agentRunId(),
                data.agentType(),
                data.subSessionId(),
                data.role(),
                data.tier(),
                data.maxSandboxes(),
                data.tokenQuota(),
                data.title(),
                data.inputJson(),
                WorkspaceIsolationMode.fromStorage(data.workspaceMode()),
                data.maxAttempts(),
                data.retryMode(),
                data.retryBaseSeconds(),
                data.lastAttemptNo());
    }

    private AttemptRef toDomain(TaskLeaseAttemptData data) {
        return new AttemptRef(
                data.attemptId(),
                data.orgId(),
                data.runId(),
                data.taskId(),
                data.agentRunId(),
                data.attemptNo(),
                data.maxAttempts(),
                data.retryMode(),
                data.retryBaseSeconds());
    }
}
