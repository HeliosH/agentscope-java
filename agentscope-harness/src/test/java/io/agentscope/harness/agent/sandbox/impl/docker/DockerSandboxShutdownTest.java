/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DockerSandboxShutdownTest {
    private static final String ID = "a".repeat(64);

    @Test
    void removalFailureMustPropagateEvenIfStopSucceeded() {
        assertThrows(IllegalStateException.class, () -> sandbox("permission denied").shutdown());
    }

    @Test
    void alreadyGoneIsAnIdempotentSuccess() {
        assertDoesNotThrow(
                () -> sandbox("Error response from daemon: No such container: " + ID).shutdown());
    }

    @Test
    void reaperRequiresFullIdNotNamePrefixOrCliOption() {
        var terminator = new DockerSandboxTerminator();
        for (String value :
                new String[] {"ubuntu", "abc123", "--all", "a".repeat(63), "A".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> terminator.terminate(value));
        }
    }

    private DockerSandbox sandbox(String error) {
        var state = new DockerSandboxState();
        state.setContainerId(ID);
        var spec = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        spec.setRoot("/workspace");
        state.setWorkspaceSpec(spec);
        return new DockerSandbox(state) {
            @Override
            protected void runDockerCliBlocking(int timeout, String... command) {
                if (command[1].equals("rm")) throw new IllegalStateException(error);
            }
        };
    }
}
