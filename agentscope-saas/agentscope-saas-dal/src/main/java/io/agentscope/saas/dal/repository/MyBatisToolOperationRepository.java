/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.ToolOperationData;
import io.agentscope.saas.dal.mybatis.admin.ToolOperationMapper;
import io.agentscope.saas.domain.orchestration.ToolOperation;
import io.agentscope.saas.domain.orchestration.ToolOperationRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the durable tool execution journal. */
@Repository
public class MyBatisToolOperationRepository implements ToolOperationRepository {
    private final ToolOperationMapper mapper;

    public MyBatisToolOperationRepository(ToolOperationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int insertClaimed(NewOperation operation) {
        return mapper.insertClaimed(
                operation.id(),
                operation.orgId(),
                operation.runId(),
                operation.taskId(),
                operation.agentRunId(),
                operation.attemptId(),
                operation.operationKey(),
                operation.invocationId(),
                operation.stepId(),
                operation.toolCallId(),
                operation.toolName(),
                operation.inputHash(),
                operation.inputJson(),
                operation.retrySafety(),
                operation.preparedAt());
    }

    @Override
    public Optional<ToolOperation> lock(UUID orgId, UUID runId, String operationKey) {
        return mapper.lock(orgId, runId, operationKey).stream()
                .findFirst()
                .map(MyBatisToolOperationRepository::toDomain);
    }

    @Override
    public boolean executionScopeActive(
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner,
            OffsetDateTime now) {
        return attemptId != null
                && mapper.countActiveExecutionScope(
                                orgId, runId, taskId, agentRunId, attemptId, leaseOwner, now)
                        == 1;
    }

    @Override
    public boolean reclaim(
            UUID id,
            UUID orgId,
            long expectedVersion,
            UUID invocationId,
            UUID attemptId,
            String stepId,
            String retrySafety,
            OffsetDateTime now) {
        return mapper.reclaim(
                        id,
                        orgId,
                        expectedVersion,
                        invocationId,
                        attemptId,
                        stepId,
                        retrySafety,
                        now)
                == 1;
    }

    @Override
    public boolean markRunning(
            UUID id, UUID orgId, UUID invocationId, String leaseOwner, OffsetDateTime now) {
        return mapper.markRunning(id, orgId, invocationId, leaseOwner, now) == 1;
    }

    @Override
    public boolean complete(
            UUID id,
            UUID orgId,
            UUID invocationId,
            String status,
            String resultJson,
            String errorType,
            String errorMessage,
            String leaseOwner,
            OffsetDateTime now) {
        return mapper.complete(
                        id,
                        orgId,
                        invocationId,
                        status,
                        resultJson,
                        errorType,
                        errorMessage,
                        leaseOwner,
                        now)
                == 1;
    }

    @Override
    public boolean markOutcomeUnknownIfAttemptInactive(
            UUID id,
            UUID orgId,
            long expectedVersion,
            String errorType,
            String errorMessage,
            OffsetDateTime now) {
        return mapper.markOutcomeUnknownIfAttemptInactive(
                        id, orgId, expectedVersion, errorType, errorMessage, now)
                == 1;
    }

    @Override
    public void appendTerminalOutbox(
            UUID outboxId, UUID orgId, UUID operationId, String eventType, String payloadJson) {
        if (mapper.appendTerminalOutbox(outboxId, orgId, operationId, eventType, payloadJson)
                != 1) {
            throw new IllegalStateException("Failed to append tool operation outbox event");
        }
    }

    private static ToolOperation toDomain(ToolOperationData row) {
        return new ToolOperation(
                row.id(),
                row.orgId(),
                row.runId(),
                row.taskId(),
                row.agentRunId(),
                row.attemptId(),
                row.operationKey(),
                row.invocationId(),
                row.stepId(),
                row.toolCallId(),
                row.toolName(),
                row.inputHash(),
                row.inputJson(),
                row.retrySafety(),
                row.status(),
                row.resultJson(),
                row.errorType(),
                row.errorMessage(),
                row.preparedAt(),
                row.startedAt(),
                row.completedAt(),
                row.updatedAt(),
                row.version());
    }
}
