/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.sandbox.SandboxCapability;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxConfigCapabilityTest {

    private final SandboxConfig config = new SandboxConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsDeploymentWhenActiveProviderSatisfiesRequiredCapabilities() {
        SaasProperties properties = properties("docker");
        properties
                .getSandbox()
                .setRequiredCapabilities(List.of("snapshot", "resource-limits", "CUSTOM_IMAGE"));

        var deployment = config.activeSandboxDeployment(properties, objectMapper);

        assertThat(deployment.providerId()).isEqualTo("docker");
        assertThat(deployment.capabilities())
                .containsExactlyInAnyOrder(
                        SandboxCapability.SNAPSHOT,
                        SandboxCapability.RESOURCE_LIMITS,
                        SandboxCapability.CUSTOM_IMAGE);
    }

    @Test
    void rejectsDeploymentWhenActiveProviderMissesRequiredCapability() {
        SaasProperties properties = properties("e2b");
        properties.getSandbox().setRequiredCapabilities(List.of("RESOURCE_LIMITS"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2b")
                .hasMessageContaining("RESOURCE_LIMITS");
    }

    @Test
    void rejectsUnknownCapabilityNameInsteadOfIgnoringIt() {
        SaasProperties properties = properties("opensandbox");
        properties.getSandbox().setRequiredCapabilities(List.of("GPU_MAGIC"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown sandbox capability");
    }

    @Test
    void treatsBlankEnvironmentCapabilityValueAsNoRequiredBaseline() {
        SaasProperties properties = properties("e2b");
        properties.getSandbox().setRequiredCapabilities(List.of(""));

        assertThat(config.activeSandboxDeployment(properties, objectMapper).providerId())
                .isEqualTo("e2b");
    }

    @Test
    void snapshotCapabilityRequiresDurableSnapshotConfiguration() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().getSnapshot().setEnabled(false);
        properties.getSandbox().setRequiredCapabilities(List.of("SNAPSHOT"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT");
    }

    private static SaasProperties properties(String provider) {
        SaasProperties properties = new SaasProperties();
        properties.getSandbox().setType(provider);
        return properties;
    }
}
