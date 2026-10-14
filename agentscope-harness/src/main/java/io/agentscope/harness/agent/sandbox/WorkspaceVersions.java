/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.StepSnapshot;
import java.util.Map;

/** The environment version includes every immutable workspace source used by a model step. */
public final class WorkspaceVersions {
    private WorkspaceVersions() {}

    public static String current(RuntimeContext context) {
        var inputs = context.get(WorkspaceInputPlan.class);
        var system = context.get(WorkspaceSystemSnapshotStore.Plan.class);
        var checkpoint = context.get(WorkspaceRestorePlan.class);
        if (inputs == null && system == null && checkpoint == null) return null;
        if (inputs == null && system == null) return checkpoint.workspaceVersion();
        return StepSnapshot.fingerprint(
                Map.of(
                        "inputVersion", inputs == null ? "" : inputs.workspace().workspaceVersion(),
                        "systemVersion",
                                system == null ? "" : system.workspace().workspaceVersion(),
                        "checkpointVersion",
                                checkpoint == null ? "" : checkpoint.workspaceVersion()));
    }
}
