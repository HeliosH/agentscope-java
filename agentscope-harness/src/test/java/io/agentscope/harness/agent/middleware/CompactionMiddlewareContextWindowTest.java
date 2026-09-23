/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class CompactionMiddlewareContextWindowTest {

    @TempDir Path workspace;

    @Test
    void selectedSmallWindowDynamicallySetsThresholdAndCompactsBeforeReasoning() {
        ContextModel model = new ContextModel();
        List<Msg> conversation = new ArrayList<>();
        conversation.add(message(MsgRole.USER, "a".repeat(3_000), Map.of()));
        conversation.add(message(MsgRole.ASSISTANT, "b".repeat(3_000), Map.of()));
        conversation.add(message(MsgRole.USER, "c".repeat(3_000), Map.of()));
        conversation.add(
                message(
                        MsgRole.USER,
                        "latest request",
                        Map.of(ContextWindowAwareModel.MODEL_ID_KEY, "small")));
        Msg system = message(MsgRole.SYSTEM, "system", Map.of());
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(system);
        inputMessages.addAll(conversation);
        AgentState state = AgentState.builder().context(conversation).build();
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .agentState(state)
                        .put(ContextWindowAwareModel.MODEL_ID_KEY, "small")
                        .build();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("assistant");
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .triggerTokens(0)
                        .keepTokens(1_000)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();

        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            CompactionMiddleware middleware = new CompactionMiddleware(manager, model, config);
            middleware
                    .onReasoning(
                            agent,
                            context,
                            new ReasoningInput(inputMessages, List.of(), null),
                            next -> {
                                forwarded.set(next);
                                return Flux.empty();
                            })
                    .blockLast();
        }

        assertEquals(1, model.summaryCalls.get());
        assertTrue(forwarded.get().messages().size() < inputMessages.size());
        assertEquals(
                "small",
                forwarded
                        .get()
                        .messages()
                        .get(1)
                        .getMetadata()
                        .get(ContextWindowAwareModel.MODEL_ID_KEY));
        assertTrue(
                TokenCounterUtil.calculateToken(forwarded.get().messages(), List.of())
                        <= model.small.inputTokenBudget());
        assertEquals(
                ConversationCompactor.SUMMARY_MSG_NAME, state.contextMutable().get(0).getName());
    }

    @Test
    void modelSwitchDynamicallyUsesOneMillionTokenProfileInsteadOfFixedThreshold() {
        ContextModel model = new ContextModel();
        List<Msg> conversation =
                List.of(
                        message(MsgRole.USER, "a".repeat(3_000), Map.of()),
                        message(MsgRole.ASSISTANT, "b".repeat(3_000), Map.of()),
                        message(MsgRole.USER, "c".repeat(3_000), Map.of()));
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(message(MsgRole.SYSTEM, "system", Map.of()));
        inputMessages.addAll(conversation);
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("assistant");
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .triggerTokens(0)
                        .keepTokens(1_000)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();

        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            CompactionMiddleware middleware = new CompactionMiddleware(manager, model, config);
            AtomicReference<ReasoningInput> largeForwarded = new AtomicReference<>();
            middleware
                    .onReasoning(
                            agent,
                            RuntimeContext.builder()
                                    .sessionId("large-session")
                                    .agentState(
                                            AgentState.builder()
                                                    .context(new ArrayList<>(conversation))
                                                    .build())
                                    .put(ContextWindowAwareModel.MODEL_ID_KEY, "large")
                                    .build(),
                            new ReasoningInput(inputMessages, List.of(), null),
                            next -> {
                                largeForwarded.set(next);
                                return Flux.empty();
                            })
                    .blockLast();

            assertSame(inputMessages, largeForwarded.get().messages());
            assertEquals(0, model.summaryCalls.get());

            middleware
                    .onReasoning(
                            agent,
                            RuntimeContext.builder()
                                    .sessionId("small-session")
                                    .agentState(
                                            AgentState.builder()
                                                    .context(new ArrayList<>(conversation))
                                                    .build())
                                    .put(ContextWindowAwareModel.MODEL_ID_KEY, "small")
                                    .build(),
                            new ReasoningInput(inputMessages, List.of(), null),
                            next -> Flux.empty())
                    .blockLast();
        }

        assertEquals(1, model.summaryCalls.get());
        assertEquals(1_000_000, model.large.contextWindowTokens());
        assertEquals(131_072, model.large.maxOutputTokens());
        assertEquals(852_544, model.large.inputTokenBudget());
    }

    @Test
    void downstreamModelFailureIsNotRetriedAsCompactionFallback() {
        ContextModel model = new ContextModel();
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("model connection failed");
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("assistant");
        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            var middleware =
                    new CompactionMiddleware(
                            manager,
                            model,
                            CompactionConfig.builder()
                                    .triggerMessages(0)
                                    .triggerTokens(0)
                                    .flushBeforeCompact(false)
                                    .offloadBeforeCompact(false)
                                    .build());
            var actual =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    middleware
                                            .onReasoning(
                                                    agent,
                                                    RuntimeContext.empty(),
                                                    new ReasoningInput(
                                                            List.of(
                                                                    message(
                                                                            MsgRole.USER,
                                                                            "hello",
                                                                            Map.of())),
                                                            List.of(),
                                                            null),
                                                    input -> {
                                                        calls.incrementAndGet();
                                                        return Flux.error(failure);
                                                    })
                                            .blockLast());
            assertSame(failure, actual);
        }
        assertEquals(1, calls.get());
    }

    @Test
    void oversizedInputCannotEscapeBudgetThroughErrorFallback() {
        ContextModel model = new ContextModel();
        var calls = new AtomicInteger();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("assistant");
        // A single oversized latest message cannot be discarded or usefully compacted.
        var input =
                new ReasoningInput(
                        List.of(
                                message(
                                        MsgRole.USER,
                                        "x".repeat(20000),
                                        Map.of(ContextWindowAwareModel.MODEL_ID_KEY, "small"))),
                        List.of(),
                        null);
        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            var middleware =
                    new CompactionMiddleware(
                            manager,
                            model,
                            CompactionConfig.builder()
                                    .triggerMessages(0)
                                    .triggerTokens(0)
                                    .flushBeforeCompact(false)
                                    .offloadBeforeCompact(false)
                                    .build());
            assertThrows(
                    ContextWindowExceededException.class,
                    () ->
                            middleware
                                    .onReasoning(
                                            agent,
                                            RuntimeContext.empty(),
                                            input,
                                            request -> {
                                                calls.incrementAndGet();
                                                return Flux.empty();
                                            })
                                    .blockLast());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void mediaBudgetUsesModelEstimatorBeforeForwarding() {
        var image =
                io.agentscope.core.message.ImageBlock.builder()
                        .source(
                                io.agentscope.core.message.URLSource.builder()
                                        .url("https://example.invalid/image.png")
                                        .build())
                        .build();
        var message =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(image)
                        .metadata(Map.of(ContextWindowAwareModel.MODEL_ID_KEY, "small"))
                        .build();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("media-budget");
        var calls = new AtomicInteger();
        var config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .triggerTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();
        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            var oversized = new CompactionMiddleware(manager, new ContextModel(5000), config);
            assertThrows(
                    ContextWindowExceededException.class,
                    () ->
                            oversized
                                    .onReasoning(
                                            agent,
                                            RuntimeContext.empty(),
                                            new ReasoningInput(List.of(message), List.of(), null),
                                            input -> {
                                                calls.incrementAndGet();
                                                return Flux.empty();
                                            })
                                    .blockLast());
            assertEquals(0, calls.get());
            new CompactionMiddleware(manager, new ContextModel(600), config)
                    .onReasoning(
                            agent,
                            RuntimeContext.empty(),
                            new ReasoningInput(List.of(message), List.of(), null),
                            input -> {
                                calls.incrementAndGet();
                                return Flux.empty();
                            })
                    .blockLast();
            assertEquals(1, calls.get());
        }
    }

    private static Msg message(MsgRole role, String text, Map<String, Object> metadata) {
        return Msg.builder().role(role).textContent(text).metadata(metadata).build();
    }

    private static final class ContextModel
            implements io.agentscope.core.model.InputTokenAwareModel, ContextWindowAwareModel {
        private final int mediaTokens;

        ContextModel() {
            this(1500);
        }

        ContextModel(int mediaTokens) {
            this.mediaTokens = mediaTokens;
        }

        @Override
        public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
            return TokenCounterUtil.calculateToken(messages, tools, block -> mediaTokens);
        }

        private final ModelContextProfile small = new ModelContextProfile("small", 4_096, 512, 512);
        private final ModelContextProfile large =
                new ModelContextProfile("large", 1_000_000, 131_072, 16_384);
        private final AtomicInteger summaryCalls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            summaryCalls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("compact summary").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "context-model";
        }

        @Override
        public ModelContextProfile resolveContextProfile(List<Msg> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Object id = messages.get(i).getMetadata().get(MODEL_ID_KEY);
                if ("small".equals(id)) {
                    return small;
                }
            }
            return large;
        }

        @Override
        public ModelContextProfile resolveContextProfile(RuntimeContext context) {
            return "small".equals(context.get(MODEL_ID_KEY)) ? small : large;
        }
    }
}
