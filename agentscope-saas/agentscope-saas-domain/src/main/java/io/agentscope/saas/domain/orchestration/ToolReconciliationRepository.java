/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only external-outcome evidence and single-use execution approvals. */
public interface ToolReconciliationRepository {
    enum Decision {
        CONFIRMED_SUCCESS,
        CONFIRMED_NOT_PERFORMED,
        UNRESOLVED
    }

    enum ClaimAuthority {
        NOT_REQUIRED,
        VALID,
        INVALID
    }

    record RunScope(
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            long sessionGeneration,
            long runVersion,
            String status,
            boolean cancelRequested) {}

    record Operation(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String operationKey,
            String toolCallId,
            String toolName,
            String inputHash,
            String retrySafety,
            String status,
            long version,
            long runVersion,
            OffsetDateTime preparedAt,
            String errorType,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            String toolContractHash,
            boolean toolContractRecoverable,
            String logicalOriginId) {}

    record Resolution(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID operationId,
            long sequence,
            long operationVersion,
            UUID actorId,
            String commandKey,
            String commandHash,
            String decision,
            String reason,
            String evidenceRef,
            String resultJson,
            String sourceFactJson,
            boolean replayApproved,
            OffsetDateTime createdAt) {}

    record ReplayPermit(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID operationId,
            UUID resolutionId,
            long operationVersion,
            UUID actorId,
            String toolName,
            String toolCallId,
            String inputHash,
            OffsetDateTime validUntil) {}

    Optional<RunScope> run(UUID org, UUID run);

    Optional<Operation> lockOperation(UUID org, UUID run, UUID operation);

    List<Operation> operations(UUID org, UUID run, UUID after, int limit);

    List<Operation> organizationOperations(UUID org, String state, UUID after, int limit);

    Optional<Resolution> command(UUID org, UUID actor, String key);

    Optional<Resolution> latest(UUID org, UUID run, UUID operation);

    List<Resolution> history(UUID org, UUID run, UUID operation, long after, int limit);

    void append(Resolution resolution);

    void approve(ReplayPermit permit);

    /** Used only within the journal's already authorized administrative execution transaction. */
    Optional<Resolution> executionResolution(UUID org, UUID run, UUID operation);

    boolean consumeReplay(
            UUID org,
            UUID run,
            UUID operation,
            long operationVersion,
            UUID resolution,
            UUID invocation,
            String toolName,
            String toolCallId,
            String inputHash,
            OffsetDateTime now);

    ClaimAuthority claimAuthority(
            UUID org,
            UUID run,
            UUID operation,
            long operationVersion,
            UUID invocation,
            String toolName,
            String toolCallId,
            String inputHash,
            OffsetDateTime now);
}
