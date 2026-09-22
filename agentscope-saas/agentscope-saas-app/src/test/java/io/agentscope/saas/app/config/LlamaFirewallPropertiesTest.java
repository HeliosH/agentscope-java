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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlamaFirewallPropertiesTest {

    @Test
    void missingConfigurationDefaultsToDisabledFailSafeAdapter() {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:18082");
        assertThat(properties.getScanPath()).isEqualTo("/v1/scan");
        assertThat(properties.getFailureMode())
                .isEqualTo(LlamaFirewallProperties.FailureMode.LOCAL_GUARD_ONLY);
        assertThat(properties.getDecisionTimeoutMillis()).isEqualTo(2000);
        assertThat(properties.getMaxContentChars()).isEqualTo(100_000);
        assertThat(properties.getCircuitFailureThreshold()).isEqualTo(3);
        assertThat(properties.getCircuitOpenDurationMillis()).isEqualTo(30_000);
    }
}
