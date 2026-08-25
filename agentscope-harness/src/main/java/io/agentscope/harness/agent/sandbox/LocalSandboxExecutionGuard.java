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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Serializes access to each sandbox state slot within one JVM. */
public final class LocalSandboxExecutionGuard implements SandboxExecutionGuard {

    private final ConcurrentMap<SandboxIsolationKey, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        Slot slot =
                slots.compute(
                        key,
                        (ignored, existing) -> {
                            Slot selected = existing != null ? existing : new Slot();
                            selected.references.incrementAndGet();
                            return selected;
                        });
        boolean acquired = false;
        try {
            slot.permit.acquire();
            acquired = true;
        } finally {
            if (!acquired) {
                releaseReference(key, slot);
            }
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                slot.permit.release();
                releaseReference(key, slot);
            }
        };
    }

    private void releaseReference(SandboxIsolationKey key, Slot slot) {
        slots.computeIfPresent(
                key,
                (ignored, current) -> {
                    if (current != slot) {
                        return current;
                    }
                    return slot.references.decrementAndGet() == 0 ? null : slot;
                });
    }

    private static final class Slot {
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicInteger references = new AtomicInteger();
    }
}
