/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Flat SQL row, kept separate from the invocation domain port. */
public record ModelInvocationData(
        UUID id,
        UUID orgId,
        UUID userId,
        UUID runId,
        UUID taskId,
        UUID agentRunId,
        UUID attemptId,
        String leaseOwner,
        String purpose,
        String modelId,
        String routeVersion,
        String status,
        long reservedTokens,
        long reservedCostMicros,
        long estimatedInputTokens,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String usageSource,
        OffsetDateTime deadlineAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String reasonCode) {}
