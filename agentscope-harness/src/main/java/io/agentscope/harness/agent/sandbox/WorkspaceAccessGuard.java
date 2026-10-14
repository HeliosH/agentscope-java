/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;

/** Revalidates durable working-copy authorization before an active filesystem operation. */
@FunctionalInterface
public interface WorkspaceAccessGuard {
    void requireCurrent(RuntimeContext context);
}
