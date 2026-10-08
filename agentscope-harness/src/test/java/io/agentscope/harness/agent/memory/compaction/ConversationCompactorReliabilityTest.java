/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ConversationCompactorReliabilityTest {
    private final List<Msg> history =
            List.of(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name(ConversationCompactor.SUMMARY_MSG_NAME)
                            .textContent("Previously verified project facts")
                            .build(),
                    Msg.builder().role(MsgRole.ASSISTANT).textContent("Recent work").build(),
                    Msg.builder().role(MsgRole.USER).textContent("Continue").build());
    private final CompactionConfig config =
            CompactionConfig.builder()
                    .triggerMessages(3)
                    .keepMessages(1)
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    .build();

    @Test
    void largePrefixPagesDoNotSilentlyDropEarliestHistory() {
        var requests = new ArrayList<String>();
        var provider =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "paged-summary";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        requests.add(messages.get(0).getTextContent());
                        return Flux.just(response("Accumulated facts " + requests.size(), "stop"));
                    }
                };
        var largeHistory =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(
                                        "EARLIEST_FACT " + "history ".repeat(1000) + "LATEST_FACT")
                                .build(),
                        Msg.builder().role(MsgRole.USER).textContent("Continue").build());
        var paging =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .summaryPrompt("Summarize all facts: {messages}")
                        .maxSummaryInputTokens(256)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();
        new ConversationCompactor(provider, new MemoryFlushManager(null, provider))
                .compactIfNeeded(RuntimeContext.empty(), largeHistory, paging, "agent", "session")
                .block();
        assertTrue(requests.size() > 1);
        assertTrue(requests.get(0).contains("EARLIEST_FACT"));
        assertTrue(requests.get(requests.size() - 1).contains("LATEST_FACT"));
        assertTrue(requests.get(1).contains("Accumulated facts 1"));
        assertTrue(
                requests.stream()
                        .allMatch(
                                text ->
                                        TokenCounterUtil.calculateToken(
                                                        List.of(
                                                                Msg.builder()
                                                                        .role(MsgRole.USER)
                                                                        .textContent(text)
                                                                        .build()))
                                                <= 256));
    }

    @Test
    void chainedSummaryIncludesPreviousSummaryRatherThanForgettingIt() {
        var captured = new AtomicReference<List<Msg>>();
        var result =
                compactor(Flux.just(response("Summary", "stop")), captured)
                        .compactIfNeeded(
                                RuntimeContext.empty(), history, config, "agent", "session")
                        .block()
                        .orElseThrow();
        assertTrue(
                captured.get()
                        .get(0)
                        .getTextContent()
                        .contains("Previously verified project facts"));
        assertTrue(result.get(0).getTextContent().contains("Summary"));
    }

    @Test
    void summarizationFailureCannotBecomeAReplacementHistory() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        compactor(
                                        Flux.error(new IllegalStateException("provider failed")),
                                        new AtomicReference<>())
                                .compactIfNeeded(
                                        RuntimeContext.empty(), history, config, "agent", "session")
                                .block());
    }

    @Test
    void truncatedOutputCannotBecomeAReplacementHistory() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        compactor(Flux.just(response("Partial", "length")), new AtomicReference<>())
                                .compactIfNeeded(
                                        RuntimeContext.empty(), history, config, "agent", "session")
                                .block());
    }

    @Test
    void emptyOutputCannotBecomeAReplacementHistory() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        compactor(Flux.empty(), new AtomicReference<>())
                                .compactIfNeeded(
                                        RuntimeContext.empty(), history, config, "agent", "session")
                                .block());
    }

    private static ConversationCompactor compactor(
            Flux<ChatResponse> responses, AtomicReference<List<Msg>> captured) {
        var model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "summary-test";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        captured.set(messages);
                        return responses;
                    }
                };
        return new ConversationCompactor(model, new MemoryFlushManager(null, model));
    }

    private static ChatResponse response(String text, String reason) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(reason)
                .build();
    }
}
