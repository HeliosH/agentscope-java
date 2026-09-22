/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import io.agentscope.saas.app.config.LlamaFirewallProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports optional scanner degradation without failing application readiness. */
@Component("llamaFirewallHealthIndicator")
public class LlamaFirewallHealthIndicator implements HealthIndicator {

    private final LlamaFirewallProperties properties;
    private final LlamaFirewallCircuitBreaker circuitBreaker;

    public LlamaFirewallHealthIndicator(
            LlamaFirewallProperties properties, LlamaFirewallCircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        LlamaFirewallCircuitBreaker.Snapshot snapshot = circuitBreaker.snapshot();
        boolean configured = properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
        boolean degraded =
                properties.isEnabled()
                        && (!configured
                                || snapshot.state() != LlamaFirewallCircuitBreaker.State.CLOSED);
        return Health.up()
                .withDetail("enabled", properties.isEnabled())
                .withDetail("configured", configured)
                .withDetail("degraded", degraded)
                .withDetail("circuitState", snapshot.state().name())
                .withDetail("consecutiveFailures", snapshot.consecutiveFailures())
                .withDetail("retryAfterMillis", snapshot.retryAfterMillis())
                .build();
    }
}
