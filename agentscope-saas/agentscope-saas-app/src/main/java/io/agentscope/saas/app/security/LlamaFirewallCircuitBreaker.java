/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import io.agentscope.saas.app.config.LlamaFirewallProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Dependency-local circuit breaker with one half-open recovery probe. */
@Component
public class LlamaFirewallCircuitBreaker {

    private final int failureThreshold;
    private final long openDurationNanos;
    private final LongSupplier nanoTime;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtNanos = new AtomicLong();
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    @Autowired
    public LlamaFirewallCircuitBreaker(LlamaFirewallProperties properties) {
        this(
                properties.getCircuitFailureThreshold(),
                properties.getCircuitOpenDurationMillis(),
                System::nanoTime);
    }

    LlamaFirewallCircuitBreaker(
            int failureThreshold, long openDurationMillis, LongSupplier nanoTime) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, openDurationMillis));
        this.nanoTime = nanoTime;
    }

    Permit acquirePermit() {
        State current = state.get();
        if (current == State.CLOSED) {
            return Permit.NORMAL;
        }
        if (current == State.OPEN) {
            long elapsed = nanoTime.getAsLong() - openedAtNanos.get();
            if (elapsed < openDurationNanos || !state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                return Permit.REJECTED;
            }
        }
        return probeInFlight.compareAndSet(false, true) ? Permit.PROBE : Permit.REJECTED;
    }

    void recordSuccess(Permit permit) {
        if (permit == Permit.REJECTED) {
            return;
        }
        if (permit == Permit.NORMAL && state.get() != State.CLOSED) {
            return;
        }
        consecutiveFailures.set(0);
        probeInFlight.set(false);
        state.set(State.CLOSED);
    }

    void recordFailure(Permit permit) {
        if (permit == Permit.REJECTED) {
            return;
        }
        if (permit == Permit.PROBE) {
            reopen();
            return;
        }
        if (state.get() != State.CLOSED) {
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            reopen();
        }
    }

    public Snapshot snapshot() {
        State current = state.get();
        long remainingMillis = 0;
        if (current == State.OPEN) {
            long elapsed = Math.max(0, nanoTime.getAsLong() - openedAtNanos.get());
            remainingMillis =
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0, openDurationNanos - elapsed));
        }
        return new Snapshot(current, consecutiveFailures.get(), remainingMillis);
    }

    private void reopen() {
        openedAtNanos.set(nanoTime.getAsLong());
        probeInFlight.set(false);
        state.set(State.OPEN);
    }

    enum Permit {
        NORMAL,
        PROBE,
        REJECTED
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public record Snapshot(State state, int consecutiveFailures, long retryAfterMillis) {}
}
