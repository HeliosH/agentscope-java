/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LlamaFirewallCircuitBreakerTest {

    @Test
    void opensAfterThresholdAndAllowsOneRecoveryProbe() {
        AtomicLong ticker = new AtomicLong();
        LlamaFirewallCircuitBreaker breaker =
                new LlamaFirewallCircuitBreaker(3, 1_000, ticker::get);

        breaker.recordFailure(breaker.acquirePermit());
        breaker.recordFailure(breaker.acquirePermit());
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.CLOSED);

        breaker.recordFailure(breaker.acquirePermit());
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.OPEN);
        assertThat(breaker.acquirePermit()).isEqualTo(LlamaFirewallCircuitBreaker.Permit.REJECTED);

        ticker.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000));
        LlamaFirewallCircuitBreaker.Permit probe = breaker.acquirePermit();
        assertThat(probe).isEqualTo(LlamaFirewallCircuitBreaker.Permit.PROBE);
        assertThat(breaker.acquirePermit()).isEqualTo(LlamaFirewallCircuitBreaker.Permit.REJECTED);

        breaker.recordSuccess(probe);
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.CLOSED);
        assertThat(breaker.snapshot().consecutiveFailures()).isZero();
        assertThat(breaker.acquirePermit()).isEqualTo(LlamaFirewallCircuitBreaker.Permit.NORMAL);
    }

    @Test
    void failedRecoveryProbeReopensForAnotherFullCooldown() {
        AtomicLong ticker = new AtomicLong();
        LlamaFirewallCircuitBreaker breaker =
                new LlamaFirewallCircuitBreaker(1, 1_000, ticker::get);

        breaker.recordFailure(breaker.acquirePermit());
        ticker.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000));
        breaker.recordFailure(breaker.acquirePermit());

        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.OPEN);
        assertThat(breaker.acquirePermit()).isEqualTo(LlamaFirewallCircuitBreaker.Permit.REJECTED);
    }
}
