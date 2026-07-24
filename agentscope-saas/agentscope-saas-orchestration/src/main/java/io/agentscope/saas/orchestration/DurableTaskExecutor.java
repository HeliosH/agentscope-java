/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import java.util.UUID;

/** Provider-neutral execution port consumed by the durable task worker. */
@FunctionalInterface
public interface DurableTaskExecutor {

    ExecutionResult execute(ExecutionRequest request) throws Exception;

    record ExecutionRequest(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID taskId,
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
            String inputJson) {}

    record ExecutionResult(String outputJson) {
        public ExecutionResult {
            outputJson = outputJson == null || outputJson.isBlank() ? "{}" : outputJson;
        }
    }
}
