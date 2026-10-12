/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;

/** Authoritative resource admission and use leases, separate from the execution-slot lock. */
public interface SandboxUsageGuard {
    record Binding(String resourceId, String referenceId, boolean borrowed) {}

    Binding reserve(RuntimeContext context, SandboxIsolationKey slot);

    void activate(RuntimeContext context, Binding binding, Sandbox sandbox);

    Binding borrow(RuntimeContext context, Binding parent, Sandbox sandbox);

    void requireCurrent(RuntimeContext context, Binding binding, Sandbox sandbox);

    boolean heartbeat(RuntimeContext context, Binding binding);

    /** Prevent new borrowers and refuse completion while an existing borrower is still active. */
    void prepareCompletion(RuntimeContext context, Binding binding);

    /** Returns true only when this owner atomically acquired the right to destroy the resource. */
    boolean close(RuntimeContext context, Binding binding, boolean retained);

    void released(RuntimeContext context, Binding binding, boolean succeeded);

    default long heartbeatSeconds() {
        return 10;
    }

    static SandboxUsageGuard noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements SandboxUsageGuard {
        INSTANCE;

        public Binding reserve(RuntimeContext context, SandboxIsolationKey slot) {
            return null;
        }

        public void activate(RuntimeContext context, Binding binding, Sandbox sandbox) {}

        public Binding borrow(RuntimeContext context, Binding parent, Sandbox sandbox) {
            return null;
        }

        public void requireCurrent(RuntimeContext context, Binding binding, Sandbox sandbox) {}

        public boolean heartbeat(RuntimeContext context, Binding binding) {
            return true;
        }

        public void prepareCompletion(RuntimeContext context, Binding binding) {}

        public boolean close(RuntimeContext context, Binding binding, boolean retained) {
            return true;
        }

        public void released(RuntimeContext context, Binding binding, boolean succeeded) {}
    }
}
