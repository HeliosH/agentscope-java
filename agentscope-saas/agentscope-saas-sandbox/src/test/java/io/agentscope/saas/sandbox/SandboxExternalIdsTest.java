/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import org.junit.jupiter.api.Test;

class SandboxExternalIdsTest {
    @Test
    void unallocatedProviderMustNotFallBackToLogicalSessionId() {
        var state = new DockerSandboxState();
        state.setSessionId("logical-session");
        assertThat(SandboxExternalIds.fromState(state)).isEmpty();
        state.setContainerId("a".repeat(64));
        assertThat(SandboxExternalIds.fromState(state)).contains("a".repeat(64));
    }
}
