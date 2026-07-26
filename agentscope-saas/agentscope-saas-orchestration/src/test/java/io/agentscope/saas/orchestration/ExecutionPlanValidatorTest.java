/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.ResourceBudget;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.TaskSpec;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionPlanValidatorTest {

    private final ExecutionPlanValidator validator =
            new ExecutionPlanValidator(ExecutionPlanValidator.Limits.defaults());

    @Test
    void acceptsAParallelDagAndComputesDeterministicMetrics() {
        ExecutionPlan plan =
                plan(
                        task("research-a", List.of()),
                        task("research-b", List.of()),
                        task("synthesize", List.of("research-a", "research-b")));

        var validated = validator.validate(plan);

        assertThat(validated.depth()).isEqualTo(2);
        assertThat(validated.maxFanout()).isEqualTo(1);
        assertThat(validated.maxParallelism()).isEqualTo(2);
    }

    @Test
    void rejectsCyclesUnknownDependenciesAndDuplicateIds() {
        ExecutionPlan plan =
                plan(
                        task("first", List.of("second")),
                        task("second", List.of("first", "missing")),
                        task("second", List.of()));

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(ExecutionPlanValidator.InvalidPlanException.class)
                .hasMessageContaining("must be unique")
                .hasMessageContaining("unknown task")
                .hasMessageContaining("acyclic");
    }

    @Test
    void rejectsSecretsAndSharedWriteWorkspaces() {
        TaskSpec unsafe =
                new TaskSpec(
                        "publish",
                        "Publish report",
                        "writer",
                        List.of(),
                        Map.of("apiKey", "must-not-enter-a-plan"),
                        List.of("report"),
                        List.of("report exists"),
                        WorkspaceIsolationMode.USER_SHARED_READ_ONLY,
                        true,
                        true,
                        true,
                        0,
                        1,
                        "MANUAL",
                        ResourceBudget.unlimited());

        assertThatThrownBy(() -> validator.validate(plan(unsafe)))
                .isInstanceOf(ExecutionPlanValidator.InvalidPlanException.class)
                .hasMessageContaining("isolated workspace")
                .hasMessageContaining("credentials or secrets");
    }

    private static ExecutionPlan plan(TaskSpec... tasks) {
        return new ExecutionPlan(
                "Produce a verified report",
                List.of(tasks),
                true,
                "Parallel research",
                ResourceBudget.unlimited());
    }

    private static TaskSpec task(String id, List<String> dependencies) {
        return new TaskSpec(
                id,
                id,
                "researcher",
                dependencies,
                Map.of("prompt", id),
                List.of(id + "-output"),
                List.of("output is traceable"),
                WorkspaceIsolationMode.RUN_ISOLATED,
                false,
                false,
                true,
                0,
                2,
                "IDEMPOTENT",
                ResourceBudget.unlimited());
    }
}
