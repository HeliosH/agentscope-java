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

import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import java.util.List;
import java.util.UUID;

/** Provider-neutral execution port consumed by the durable task worker. */
@FunctionalInterface
public interface DurableTaskExecutor {

    ExecutionResult execute(ExecutionRequest request) throws Exception;

    record ExecutionRequest(
            UUID attemptId,
            String leaseOwner,
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
            String inputJson,
            WorkspaceIsolationMode workspaceIsolationMode,
            String expectedOutputJson,
            String acceptanceJson,
            List<DependencyContext> dependencies) {

        public ExecutionRequest {
            workspaceIsolationMode =
                    workspaceIsolationMode != null
                            ? workspaceIsolationMode
                            : WorkspaceIsolationMode.NONE;
            expectedOutputJson =
                    expectedOutputJson == null || expectedOutputJson.isBlank()
                            ? "[]"
                            : expectedOutputJson;
            acceptanceJson =
                    acceptanceJson == null || acceptanceJson.isBlank() ? "[]" : acceptanceJson;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public ExecutionRequest(
                UUID attemptId,
                String leaseOwner,
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
                String inputJson,
                WorkspaceIsolationMode workspaceIsolationMode) {
            this(
                    attemptId,
                    leaseOwner,
                    orgId,
                    runId,
                    taskId,
                    userId,
                    agentId,
                    sessionId,
                    agentRunId,
                    agentType,
                    subSessionId,
                    role,
                    tier,
                    maxSandboxes,
                    tokenQuota,
                    title,
                    inputJson,
                    workspaceIsolationMode,
                    "[]",
                    "[]",
                    List.of());
        }
    }

    record DependencyContext(
            UUID taskId, String title, String outputJson, List<String> artifactRefs) {
        public DependencyContext {
            artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        }
    }

    record ExecutionResult(String outputJson) {
        public ExecutionResult {
            outputJson = outputJson == null || outputJson.isBlank() ? "{}" : outputJson;
        }
    }
}
