/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/** Persists bounded system projections independently of the mutable employee workspace. */
public interface WorkspaceSystemSnapshotStore {
    interface Source {
        List<String> paths(int limit);

        WorkspaceRestorePlan.WorkspaceFile read(String path, long maxBytes);
    }

    WorkspaceRestorePlan prepare(
            RuntimeContext context, Source source, WorkspaceTransferPolicy policy) throws Exception;

    /** A frozen plan is distinct from both attached inputs and an execution checkpoint. */
    record Plan(WorkspaceRestorePlan workspace) {}
}
