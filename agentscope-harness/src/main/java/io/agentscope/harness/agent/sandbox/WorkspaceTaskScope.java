/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

/** Trusted publication namespace; physical writers are serialized by the durable scheduler. */
public record WorkspaceTaskScope(String taskId) {
    public WorkspaceTaskScope {
        taskId = java.util.UUID.fromString(taskId).toString();
    }

    public String outputRoot() {
        return "outputs/tasks/" + taskId;
    }

    public String publicationPath(String path) {
        String normalized = new WorkspaceRestorePlan.WorkspaceFile(path, new byte[0]).path();
        if (!normalized.startsWith("outputs/") || normalized.startsWith(outputRoot() + "/"))
            return "/" + normalized;
        String relative = normalized.substring("outputs/".length());
        if (relative.startsWith("tasks/")) {
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            relative =
                    "revisions/"
                            + io.agentscope.core.tool.StepSnapshot.fingerprint(relative)
                                    .substring(0, 16)
                            + "-"
                            + name;
        }
        return "/" + outputRoot() + "/" + relative;
    }
}
