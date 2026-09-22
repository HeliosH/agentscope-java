/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics for the optional LlamaFirewall dependency. */
@Component
public class LlamaFirewallMetrics {

    private final MeterRegistry registry;

    @Autowired
    public LlamaFirewallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private LlamaFirewallMetrics() {
        this.registry = null;
    }

    static LlamaFirewallMetrics noop() {
        return new LlamaFirewallMetrics();
    }

    void recordRequest(LlamaFirewallClient.Stage stage, String outcome, long durationNanos) {
        if (registry == null) {
            return;
        }
        String stageTag = stage == null ? "unknown" : stage.wireValue();
        String outcomeTag = normalize(outcome);
        Counter.builder("saas.security.llama_firewall.requests")
                .description("LlamaFirewall scan attempts and local fallback outcomes")
                .tag("stage", stageTag)
                .tag("outcome", outcomeTag)
                .register(registry)
                .increment();
        if (durationNanos >= 0) {
            Timer.builder("saas.security.llama_firewall.duration")
                    .description("LlamaFirewall scan request duration")
                    .tag("stage", stageTag)
                    .tag("outcome", outcomeTag)
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }
}
