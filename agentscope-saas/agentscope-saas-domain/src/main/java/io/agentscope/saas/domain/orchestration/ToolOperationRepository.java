/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for atomic tool-operation claims and terminal commits. */
public interface ToolOperationRepository {
    int insertClaimed(NewOperation operation);

    Optional<ToolOperation> lock(UUID orgId, UUID runId, String operationKey);

    boolean executionScopeActive(
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner,
            OffsetDateTime now);

    boolean reclaim(
            UUID id,
            UUID orgId,
            long expectedVersion,
            UUID invocationId,
            UUID attemptId,
            String stepId,
            String retrySafety,
            OffsetDateTime now);

    boolean markRunning(
            UUID id, UUID orgId, UUID invocationId, String leaseOwner, OffsetDateTime now);

    boolean complete(
            UUID id,
            UUID orgId,
            UUID invocationId,
            String status,
            String resultJson,
            String errorType,
            String errorMessage,
            String leaseOwner,
            OffsetDateTime now);

    boolean markOutcomeUnknownIfAttemptInactive(
            UUID id,
            UUID orgId,
            long expectedVersion,
            String errorType,
            String errorMessage,
            OffsetDateTime now);

    void appendTerminalOutbox(
            UUID outboxId, UUID orgId, UUID operationId, String eventType, String payloadJson);

    record NewOperation(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String operationKey,
            UUID invocationId,
            String stepId,
            String toolCallId,
            String toolName,
            String inputHash,
            String inputJson,
            String retrySafety,
            OffsetDateTime preparedAt) {}
}
