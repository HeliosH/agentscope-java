/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.orchestration;

import java.util.List;
import java.util.Map;

/** Structured scheduling fact published by a planner. */
public record ExecutionPlan(
        String goal,
        List<TaskSpec> tasks,
        boolean approvalRequired,
        String rationale,
        ResourceBudget budget) {

    public ExecutionPlan {
        tasks = tasks != null ? List.copyOf(tasks) : List.of();
        budget = budget != null ? budget : ResourceBudget.unlimited();
    }

    /** A single durable node in the execution DAG. */
    public record TaskSpec(
            String clientTaskId,
            String title,
            String agentType,
            List<String> dependsOn,
            Map<String, Object> input,
            List<String> expectedOutputs,
            List<String> acceptanceCriteria,
            WorkspaceIsolationMode workspaceMode,
            boolean writeIntent,
            boolean approvalRequired,
            boolean verificationRequired,
            int priority,
            int maxAttempts,
            String retryMode,
            ResourceBudget budget) {

        public TaskSpec {
            dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
            input = input != null ? Map.copyOf(input) : Map.of();
            expectedOutputs = expectedOutputs != null ? List.copyOf(expectedOutputs) : List.of();
            acceptanceCriteria =
                    acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : List.of();
            workspaceMode = workspaceMode != null ? workspaceMode : WorkspaceIsolationMode.NONE;
            retryMode = retryMode != null && !retryMode.isBlank() ? retryMode : "IDEMPOTENT";
            budget = budget != null ? budget : ResourceBudget.unlimited();
        }
    }

    /** Optional immutable resource estimate used by the validator and scheduler. */
    public record ResourceBudget(
            Long tokenLimit,
            Long costLimitMicros,
            Integer modelCallLimit,
            Long durationSeconds,
            Integer sandboxCount,
            Long storageBytes) {

        public static ResourceBudget unlimited() {
            return new ResourceBudget(null, null, null, null, null, null);
        }
    }
}
