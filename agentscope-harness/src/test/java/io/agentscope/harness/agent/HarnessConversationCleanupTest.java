/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.ConversationCommitter;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessConversationCleanupTest {
    @TempDir Path workspace;
    private final AtomicReference<List<Msg>> lastBatch = new AtomicReference<>(List.of());
    private final AtomicBoolean released = new AtomicBoolean();

    @Test
    void successfulSourceIsCommittedBeforeSandboxReleaseWithoutMemoryHooks() {
        var lifecycle = lifecycle();
        doAnswer(
                        call -> {
                            assertTrue(
                                    lastBatch.get().stream()
                                            .anyMatch(m -> "done".equals(m.getTextContent())));
                            released.set(true);
                            return null;
                        })
                .when(lifecycle)
                .releaseForCall(any());
        HarnessAgent agent = harness(model(Flux.just(text("done"))), archive(false), lifecycle);
        assertEquals(
                "done",
                agent.call(List.of(question()), context())
                        .block(Duration.ofSeconds(10))
                        .getTextContent());
        assertTrue(released.get());
    }

    @Test
    void failedSourceStillReleasesSandboxAndDoesNotReportSuccess() {
        var lifecycle = lifecycle();
        HarnessAgent agent = harness(model(Flux.just(text("done"))), archive(true), lifecycle);
        assertThrows(
                SessionArchiveStore.ArchiveCommitException.class,
                () -> agent.call(List.of(question()), context()).block(Duration.ofSeconds(10)));
        assertTrue(released.get());
        verify(lifecycle).releaseForCall(any());
    }

    @Test
    void cancelStopsModelAndCommitsFrozenWindowBeforeSandboxRelease() throws Exception {
        var started = new CountDownLatch(1);
        var stopped = new AtomicBoolean();
        var lifecycle = lifecycle();
        doAnswer(
                        call -> {
                            RuntimeContext context = call.getArgument(0);
                            assertTrue(stopped.get());
                            assertTrue(context.get(ConversationCommitter.Boundary.class) != null);
                            assertEquals(
                                    context.get(ConversationCommitter.Boundary.class).messages(),
                                    lastBatch.get());
                            released.set(true);
                            return null;
                        })
                .when(lifecycle)
                .releaseForCall(any());
        HarnessAgent agent =
                harness(
                        model(
                                Flux.<ChatResponse>never()
                                        .doOnSubscribe(s -> started.countDown())
                                        .doOnCancel(() -> stopped.set(true))),
                        archive(false),
                        lifecycle);
        var subscription = agent.streamEvents(List.of(question()), context()).subscribe();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(released.get());
        assertEquals(1, lastBatch.get().size());
    }

    @Test
    void earlyModelErrorStillCommitsInputAndReleasesSandbox() {
        var lifecycle = lifecycle();
        var failure = new IllegalStateException("fixture model failure");
        HarnessAgent agent = harness(model(Flux.error(failure)), archive(false), lifecycle);
        assertThrows(
                IllegalStateException.class,
                () -> agent.call(List.of(question()), context()).block(Duration.ofSeconds(10)));
        assertTrue(released.get());
        assertEquals(1, lastBatch.get().size());
    }

    private SandboxLifecycleMiddleware lifecycle() {
        var lifecycle = mock(SandboxLifecycleMiddleware.class, CALLS_REAL_METHODS);
        doNothing().when(lifecycle).acquireForCall(any());
        doAnswer(
                        call -> {
                            released.set(true);
                            return null;
                        })
                .when(lifecycle)
                .releaseForCall(any());
        return lifecycle;
    }

    private SessionArchiveStore archive(boolean failOnAssistant) {
        var archive = mock(SessionArchiveStore.class);
        when(archive.append(any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            List<Msg> messages = call.getArgument(3);
                            if (failOnAssistant
                                    && messages.stream()
                                            .anyMatch(m -> m.getRole() == MsgRole.ASSISTANT))
                                throw new IllegalStateException("fixture database failure");
                            lastBatch.set(List.copyOf(messages));
                            return null;
                        });
        return archive;
    }

    private HarnessAgent harness(
            Model model, SessionArchiveStore archive, SandboxLifecycleMiddleware lifecycle) {
        return HarnessAgent.builder()
                .name("assistant")
                .model(model)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .inheritSandboxRuntime(
                        new HarnessAgentBuilderSupport.SandboxRuntimeBinding(
                                new SandboxBackedFilesystem(),
                                lifecycle,
                                SandboxContext.builder().build()))
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableSubagents()
                .memory(MemoryConfig.builder().sessionArchiveStore(archive).build())
                .build();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("employee").sessionId("session").build();
    }

    private static Msg question() {
        return Msg.builder().role(MsgRole.USER).textContent("question").build();
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    private static Model model(Flux<ChatResponse> stream) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("fixture");
        when(model.stream(any(), any(), any())).thenReturn(stream);
        return model;
    }
}
