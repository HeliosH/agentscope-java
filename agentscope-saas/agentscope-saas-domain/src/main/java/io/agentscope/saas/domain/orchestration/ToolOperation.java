/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Durable state of one business tool operation. */
public record ToolOperation(
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
        String status,
        String resultJson,
        String errorType,
        String errorMessage,
        OffsetDateTime preparedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime updatedAt,
        long version) {}
