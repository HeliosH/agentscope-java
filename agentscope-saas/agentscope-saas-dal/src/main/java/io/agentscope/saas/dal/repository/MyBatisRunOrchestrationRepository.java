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

import io.agentscope.saas.dal.mybatis.tenant.RunOrchestrationMapper;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the tenant-scoped durable Run aggregate. */
@Repository
public class MyBatisRunOrchestrationRepository implements RunOrchestrationRepository {

    private final RunOrchestrationMapper mapper;

    public MyBatisRunOrchestrationRepository(RunOrchestrationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AssistantRun> findOwnedRun(UUID runId, UUID orgId, UUID userId, UUID agentId) {
        return first(mapper.findOwnedRun(runId, orgId, userId, agentId));
    }

    @Override
    public List<AssistantRun> findRecentOwnedRuns(
            UUID orgId, UUID userId, UUID agentId, int limit) {
        return mapper.findRecentOwnedRuns(orgId, userId, agentId, limit);
    }

    @Override
    public Optional<AssistantRun> findByIdempotencyKey(
            UUID orgId, UUID userId, UUID agentId, String idempotencyKey) {
        return first(mapper.findByIdempotencyKey(orgId, userId, agentId, idempotencyKey));
    }

    @Override
    public Optional<AssistantRun> findLatestOwnedRunBySession(
            UUID sessionId, UUID orgId, UUID userId, UUID agentId) {
        return first(mapper.findLatestOwnedRunBySession(sessionId, orgId, userId, agentId));
    }

    @Override
    public Optional<AssistantRun> lockOwnedRun(UUID runId, UUID orgId, UUID userId, UUID agentId) {
        return first(mapper.lockOwnedRun(runId, orgId, userId, agentId));
    }

    @Override
    public void insertRun(NewRun run) {
        requireOne(mapper.insertRun(run), "insert Run " + run.id());
    }

    @Override
    public void completeRun(
            UUID runId,
            UUID orgId,
            String status,
            boolean cancelRequested,
            String failureCode,
            String failureMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {
        requireOne(
                mapper.completeRun(
                        runId,
                        orgId,
                        status,
                        cancelRequested,
                        failureCode,
                        failureMessage,
                        completedAt,
                        updatedAt),
                "complete Run " + runId);
    }

    @Override
    public void touchRun(UUID runId, UUID orgId, OffsetDateTime updatedAt) {
        requireOne(mapper.touchRun(runId, orgId, updatedAt), "touch Run " + runId);
    }

    @Override
    public void reopenRun(UUID runId, UUID orgId, OffsetDateTime updatedAt) {
        requireOne(mapper.reopenRun(runId, orgId, updatedAt), "reopen Run " + runId);
    }

    @Override
    public void detachMessageReferencesForSession(UUID sessionId, UUID orgId) {
        mapper.clearTriggerMessageReferences(sessionId, orgId);
        mapper.clearSourceRunReferences(sessionId, orgId);
    }

    @Override
    public void deleteBySessionId(UUID sessionId, UUID orgId) {
        mapper.deleteBySessionId(sessionId, orgId);
    }

    @Override
    public List<TaskNode> findTasks(UUID runId, UUID orgId) {
        return mapper.findTasks(runId, orgId);
    }

    @Override
    public Optional<TaskNode> findTask(UUID taskId, UUID runId, UUID orgId) {
        return first(mapper.findTask(taskId, runId, orgId));
    }

    @Override
    public Optional<TaskNode> findTaskByExternalId(UUID runId, UUID orgId, String externalTaskId) {
        return first(mapper.findTaskByExternalId(runId, orgId, externalTaskId));
    }

    @Override
    public boolean hasUnsettledChildren(UUID runId) {
        return mapper.countUnsettledChildren(runId) > 0;
    }

    @Override
    public void insertTask(NewTask task) {
        requireOne(mapper.insertTask(task), "insert Task " + task.id());
    }

    @Override
    public void assignTaskOwner(
            UUID taskId, UUID orgId, UUID ownerAgentRunId, OffsetDateTime updatedAt) {
        requireOne(
                mapper.assignTaskOwner(taskId, orgId, ownerAgentRunId, updatedAt),
                "assign Task owner " + taskId);
    }

    @Override
    public void completeTask(
            UUID taskId,
            UUID orgId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {
        requireOne(
                mapper.completeTask(
                        taskId, orgId, status, errorCode, errorMessage, completedAt, updatedAt),
                "complete Task " + taskId);
    }

    @Override
    public void scheduleTaskContinuation(
            UUID taskId, UUID orgId, String inputJson, int maxAttempts, OffsetDateTime updatedAt) {
        requireOne(
                mapper.scheduleTaskContinuation(taskId, orgId, inputJson, maxAttempts, updatedAt),
                "schedule Task continuation " + taskId);
    }

    @Override
    public List<AgentRun> findAgentRuns(UUID runId, UUID orgId) {
        return mapper.findAgentRuns(runId, orgId);
    }

    @Override
    public void insertAgentRun(NewAgentRun agentRun) {
        requireOne(mapper.insertAgentRun(agentRun), "insert AgentRun " + agentRun.id());
    }

    @Override
    public void updateAgentRunStatus(
            UUID agentRunId,
            UUID orgId,
            String status,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {
        requireOne(
                mapper.updateAgentRunStatus(agentRunId, orgId, status, completedAt, updatedAt),
                "update AgentRun " + agentRunId);
    }

    @Override
    public List<RunAttempt> findAttempts(UUID runId, UUID orgId) {
        return mapper.findAttempts(runId, orgId);
    }

    @Override
    public void insertAttempt(NewAttempt attempt) {
        requireOne(mapper.insertAttempt(attempt), "insert Attempt " + attempt.id());
    }

    @Override
    public void updateAttemptStatus(
            UUID attemptId,
            UUID orgId,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {
        requireOne(
                mapper.updateAttemptStatus(
                        attemptId, orgId, status, errorCode, errorMessage, completedAt, updatedAt),
                "update Attempt " + attemptId);
    }

    @Override
    public long nextEventSequence(UUID runId, UUID orgId, OffsetDateTime updatedAt) {
        requireOne(
                mapper.incrementEventSequence(runId, orgId, updatedAt),
                "increment Run event sequence " + runId);
        Long sequence = mapper.findEventSequence(runId, orgId);
        if (sequence == null) {
            throw new IllegalStateException("Run event sequence not found: " + runId);
        }
        return sequence;
    }

    @Override
    public void insertEvent(NewEvent event) {
        requireOne(mapper.insertEvent(event), "insert Run event " + event.id());
    }

    @Override
    public void insertOutbox(NewOutboxMessage message) {
        requireOne(mapper.insertOutbox(message), "insert Run outbox " + message.id());
    }

    @Override
    public List<RunEvent> findEvents(
            UUID runId, UUID orgId, UUID userId, long afterSequence, int limit) {
        return mapper.findEvents(runId, orgId, userId, afterSequence, limit);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected one row while attempting to " + operation + ", updated " + updated);
        }
    }
}
