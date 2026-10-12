/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

/** Removes an exact previously allocated container without starting or hydrating it. */
public final class DockerSandboxTerminator {
    public void terminate(String containerId) throws Exception {
        if (containerId == null || !containerId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("A full Docker container ID is required");
        }
        var state = new DockerSandboxState();
        state.setContainerId(containerId);
        state.setContainerOwned(true);
        var spec = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        spec.setRoot("/workspace");
        state.setWorkspaceSpec(spec);
        new DockerSandbox(state).shutdown();
    }
}
