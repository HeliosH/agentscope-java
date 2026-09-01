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

class ClawSentryPropertiesTest {

    @Test
    void missingConfigurationDefaultsToDisabledFailSafeAdapter() {
        ClawSentryProperties properties = new ClawSentryProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(properties.getApiPath()).isEqualTo("/ahp");
        assertThat(properties.getFailureMode()).isEqualTo(ClawSentryProperties.FailureMode.ASK);
        assertThat(properties.getDecisionTimeoutMillis()).isEqualTo(1500);
    }
}
