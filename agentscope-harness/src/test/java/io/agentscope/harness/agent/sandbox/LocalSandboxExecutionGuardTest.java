/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalSandboxExecutionGuardTest {

    @Test
    void serializesCallersForTheSameSandboxSlot() throws Exception {
        LocalSandboxExecutionGuard guard = new LocalSandboxExecutionGuard();
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(
                                IsolationScope.USER,
                                RuntimeContext.builder().userId("user-1").build(),
                                "agent")
                        .orElseThrow();
        SandboxLease first = guard.tryEnter(key);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting =
                    executor.submit(
                            () -> {
                                attempted.countDown();
                                try (SandboxLease ignored = guard.tryEnter(key)) {
                                    entered.countDown();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            assertFalse(entered.await(Duration.ofMillis(100).toMillis(), TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            waiting.get(1, TimeUnit.SECONDS);
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }
}
