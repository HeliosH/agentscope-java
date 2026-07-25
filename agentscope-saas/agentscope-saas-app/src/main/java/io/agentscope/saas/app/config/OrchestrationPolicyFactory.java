/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import org.springframework.stereotype.Component;

/** Captures deployment governance configuration as immutable per-Run policy. */
@Component
public class OrchestrationPolicyFactory {

    private final SaasProperties properties;
    private final ObjectMapper objectMapper;

    public OrchestrationPolicyFactory(SaasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RunOrchestrationService.RunPolicy runPolicy() {
        SaasProperties.Orchestration config = properties.getOrchestration();
        PermissionContextState permissions =
                AgentConfig.buildPermissionContext(properties.getAgent());
        try {
            String permissionJson = objectMapper.writeValueAsString(permissions);
            return new RunOrchestrationService.RunPolicy(
                    enabled(config.getMaxRunTokens()),
                    enabled(config.getMaxRunCostMicros()),
                    enabled(config.getMaxRunModelCalls()),
                    enabled(config.getMaxRunDurationSeconds()),
                    enabled(config.getMaxTaskTokens()),
                    enabled(config.getMaxTaskCostMicros()),
                    enabled(config.getMaxTaskModelCalls()),
                    enabled(config.getMaxTaskDurationSeconds()),
                    permissionJson);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to snapshot orchestration permissions", e);
        }
    }

    private long enabled(long value) {
        return properties.getOrchestration().isBudgetEnforcementEnabled() ? Math.max(0, value) : 0;
    }

    private int enabled(int value) {
        return properties.getOrchestration().isBudgetEnforcementEnabled() ? Math.max(0, value) : 0;
    }
}
