/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.app.config.LlamaFirewallProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class LlamaFirewallHealthIndicatorTest {

    @Test
    void reportsMissingEndpointAsDegradedConfiguration() {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(" ");
        LlamaFirewallCircuitBreaker breaker = new LlamaFirewallCircuitBreaker(properties);

        var health = new LlamaFirewallHealthIndicator(properties, breaker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("configured", false)
                .containsEntry("degraded", true);
    }

    @Test
    void reportsDegradationWithoutTakingApplicationOutOfService() {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();
        properties.setEnabled(true);
        properties.setCircuitFailureThreshold(1);
        LlamaFirewallCircuitBreaker breaker = new LlamaFirewallCircuitBreaker(properties);
        breaker.recordFailure(breaker.acquirePermit());

        var health = new LlamaFirewallHealthIndicator(properties, breaker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("degraded", true)
                .containsEntry("circuitState", "OPEN");
    }
}
