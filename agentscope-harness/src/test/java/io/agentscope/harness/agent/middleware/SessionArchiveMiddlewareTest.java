/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ConversationCommitter;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SessionArchiveMiddlewareTest {
    @Test
    void errorTailIsAwaitedAndOriginalFailureIsPreserved() {
        assertErrorTail(false);
    }

    @Test
    void synchronousNextFailureAlsoCommitsTail() {
        assertErrorTail(true);
    }

    private void assertErrorTail(boolean synchronous) {
        var batches = new CopyOnWriteArrayList<List<Msg>>();
        SessionArchiveStore archive = mock(SessionArchiveStore.class);
        when(archive.append(any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            batches.add(List.copyOf(call.getArgument(3)));
                            return null;
                        });
        var context = RuntimeContext.builder().sessionId("session").build();
        var state = AgentState.builder().sessionId("session").build();
        context.setAgentState(state);
        var failure = new IllegalStateException("model unavailable");
        Msg source = message("last completed message");
        var middleware = new SessionArchiveMiddleware(archive);
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                middleware
                                        .onAgent(
                                                agent(),
                                                context,
                                                new AgentInput(List.of(message("input"))),
                                                input -> {
                                                    state.contextMutable().add(source);
                                                    if (synchronous) throw failure;
                                                    return Flux.error(failure);
                                                })
                                        .blockLast(Duration.ofSeconds(5))));
        assertEquals(2, batches.size());
        assertEquals(List.of(source), batches.get(1));
    }

    @Test
    void errorTailCommitFailureSurfacesWithOriginalFailureSuppressed() {
        SessionArchiveStore archive = mock(SessionArchiveStore.class);
        var databaseFailure = new IllegalStateException("database unavailable");
        when(archive.append(any(), any(), any(), any()))
                .thenReturn(null)
                .thenThrow(databaseFailure);
        var context = RuntimeContext.builder().sessionId("session").build();
        context.setAgentState(AgentState.builder().sessionId("session").build());
        var original = new IllegalStateException("model unavailable");
        var failure =
                assertThrows(
                        SessionArchiveStore.ArchiveCommitException.class,
                        () ->
                                new SessionArchiveMiddleware(archive)
                                        .onAgent(
                                                agent(),
                                                context,
                                                new AgentInput(List.of(message("input"))),
                                                input -> Flux.error(original))
                                        .blockLast(Duration.ofSeconds(5)));
        assertSame(databaseFailure, failure.getCause());
        assertTrue(java.util.Arrays.asList(failure.getSuppressed()).contains(original));
    }

    @Test
    void inputFailureDoesNotInstallBarrierOrStartNext() {
        SessionArchiveStore archive = mock(SessionArchiveStore.class);
        when(archive.append(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        var context = RuntimeContext.builder().sessionId("session").build();
        var next = new AtomicBoolean();
        assertThrows(
                SessionArchiveStore.ArchiveCommitException.class,
                () ->
                        new SessionArchiveMiddleware(archive)
                                .onAgent(
                                        agent(),
                                        context,
                                        new AgentInput(List.of(message("input"))),
                                        input -> {
                                            next.set(true);
                                            return Flux.empty();
                                        })
                                .blockLast(Duration.ofSeconds(5)));
        assertEquals(false, next.get());
        assertNull(context.get(ConversationCommitter.class));
    }

    private static Agent agent() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        return agent;
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
