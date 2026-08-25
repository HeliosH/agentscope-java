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
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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

    private static Msg message(MsgRole role, String text, Map<String, Object> metadata) {
        return Msg.builder().role(role).textContent(text).metadata(metadata).build();
    }

    private static final class ContextModel implements Model, ContextWindowAwareModel {
        private final ModelContextProfile small = new ModelContextProfile("small", 4_096, 512, 512);
        private final ModelContextProfile large =
                new ModelContextProfile("large", 32_768, 4_096, 1_024);
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
