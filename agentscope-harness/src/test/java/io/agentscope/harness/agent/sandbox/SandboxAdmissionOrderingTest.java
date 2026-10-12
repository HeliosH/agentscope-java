/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxAdmissionOrderingTest {
    @Test
    void admissionRunsUnderSlotLeaseAndBeforeProviderCreation() throws Exception {
        var events = new ArrayList<String>();
        SandboxExecutionGuard slots =
                key -> {
                    events.add("slot");
                    return () -> events.add("close");
                };
        var client = mock(SandboxClient.class);
        when(client.create(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            events.add("create");
                            return mock(Sandbox.class);
                        });
        var manager =
                new SandboxManager(
                        client,
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "order"),
                        "order",
                        slots,
                        null);
        var result =
                manager.acquire(
                        SandboxContext.builder().build(),
                        RuntimeContext.builder().userId("user").build(),
                        () -> events.add("admission"));
        assertEquals(List.of("slot", "admission", "create"), events);
        result.getLease().close();
        assertEquals("close", events.get(events.size() - 1));
    }

    @Test
    void admissionFailureReleasesSlotAndDoesNotCreateProviderResource() {
        var events = new ArrayList<String>();
        SandboxExecutionGuard slots =
                key -> {
                    events.add("slot");
                    return () -> events.add("close");
                };
        var manager =
                new SandboxManager(
                        mock(SandboxClient.class),
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "order"),
                        "order",
                        slots,
                        null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        manager.acquire(
                                SandboxContext.builder().build(),
                                RuntimeContext.builder().userId("user").build(),
                                () -> {
                                    events.add("admission");
                                    throw new IllegalStateException("quota");
                                }));
        assertEquals(List.of("slot", "admission", "close"), events);
    }
}
