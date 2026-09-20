/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelStreamInterruptedException;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.saas.app.config.SaasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunRecoveryCoordinatorTest {

    private SaasProperties properties;
    private RunRecoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new SaasProperties();
        properties.getModel().getStreamRecovery().setMaxAttempts(3);
        properties.getModel().getStreamRecovery().setInitialBackoffMillis(100);
        properties.getModel().getStreamRecovery().setMaxBackoffMillis(500);
        coordinator = new RunRecoveryCoordinator(properties);
    }

    @Test
    void classifiesWrappedInterruptedStreamsAndAppliesBoundedBackoff() {
        var error =
                new IllegalStateException(
                        "wrapper",
                        new ModelStreamInterruptedException(
                                "partial stream", new RuntimeException("closed"), "qwen", "gw"));

        var first = coordinator.decide(error, 1);
        var second = coordinator.decide(error, 2);

        assertThat(first.recoverable()).isTrue();
        assertThat(first.reasonCode()).isEqualTo("MODEL_STREAM_INTERRUPTED");
        assertThat(first.delayMillis()).isEqualTo(100L);
        assertThat(second.delayMillis()).isEqualTo(200L);
    }

    @Test
    void stopsAfterConfiguredAttemptLimit() {
        var decision = coordinator.decide(new ModelException("unavailable"), 3);

        assertThat(decision.recoverable()).isFalse();
        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("MODEL_REQUEST_FAILED");
    }

    @Test
    void ignoresBusinessFailuresAndDisabledPolicy() {
        assertThat(coordinator.decide(new IllegalArgumentException("bad input"), 1).recoverable())
                .isFalse();

        properties.getModel().getStreamRecovery().setEnabled(false);
        var disabled = new RunRecoveryCoordinator(properties);
        var decision = disabled.decide(new ModelException("unavailable"), 1);
        assertThat(decision.recoverable()).isFalse();
        assertThat(decision.exhausted()).isFalse();
    }

    @Test
    void doesNotRetryPermanentProviderClientErrors() {
        var unauthorized =
                new ModelException(
                        "provider rejected request",
                        OpenAIException.create(401, "invalid token", "auth", "{}"));
        var rateLimited =
                new ModelException(
                        "provider throttled request",
                        OpenAIException.create(429, "slow down", "rate_limit", "{}"));

        assertThat(coordinator.decide(unauthorized, 1).recoverable()).isFalse();
        assertThat(coordinator.decide(rateLimited, 1).recoverable()).isTrue();
    }
}
