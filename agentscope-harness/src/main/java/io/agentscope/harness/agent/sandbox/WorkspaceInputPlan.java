/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

/** Trusted, bounded working copies of explicitly bound file versions. Not a retry checkpoint. */
public record WorkspaceInputPlan(WorkspaceRestorePlan workspace, WorkspaceTaskScope taskScope) {
    public WorkspaceInputPlan(WorkspaceRestorePlan workspace) {
        this(workspace, null);
    }

    /** Copied with the parent context so borrowed agents cannot silently change input bindings. */
    public record Materialized(String environmentId, String version) {}

    public WorkspaceInputPlan {
        java.util.Objects.requireNonNull(workspace);
        for (var file : workspace.files())
            if (!(file.path().startsWith("inputs/") || file.path().startsWith("outputs/")))
                throw new IllegalArgumentException("Input must be a user file");
    }
}
