/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.config;

import io.agentscope.saas.orchestration.ExecutionPlanValidator;
import io.agentscope.saas.orchestration.ExecutionPlanValidator.Limits;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Deployment-level structured planner policy. */
@Configuration
public class ExecutionPlanningConfig {

    @Bean
    ExecutionPlanValidator executionPlanValidator(SaasProperties properties) {
        SaasProperties.Orchestration orchestration = properties.getOrchestration();
        SaasProperties.Subagents subagents = properties.getSubagents();
        return new ExecutionPlanValidator(
                new Limits(
                        Math.max(1, subagents.getMaxTasksPerRun()),
                        Math.max(1, orchestration.getPlannerMaxDepth()),
                        Math.max(1, orchestration.getPlannerMaxFanout()),
                        Math.max(1, orchestration.getPlannerMaxParallelism()),
                        Math.max(1, orchestration.getPlannerMaxAttempts()),
                        2000,
                        500,
                        Math.max(1, orchestration.getPlannerMaxInputCharacters()),
                        orchestration.getMaxRunTokens(),
                        orchestration.getMaxRunCostMicros(),
                        orchestration.getMaxRunModelCalls(),
                        orchestration.getMaxRunDurationSeconds(),
                        Math.max(1, orchestration.getPlannerMaxSandboxes()),
                        Math.max(1, orchestration.getPlannerMaxStorageBytes()),
                        Set.of()));
    }
}
