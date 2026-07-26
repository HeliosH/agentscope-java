/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.util.UUID;

/** Persistence projection for a dependency-ready durable task. */
public record TaskLeaseCandidateData(
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
        String workspaceMode,
        int maxAttempts,
        String retryMode,
        int retryBaseSeconds,
        int lastAttemptNo) {}
