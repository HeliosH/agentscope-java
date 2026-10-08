/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.PurposeBindableModel;
import io.agentscope.core.model.PurposeBindableModel.InvocationLimits;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class PurposeTextFoldTest {
    private final RuntimeContext context = RuntimeContext.empty();

    @Test
    void visitsEveryCharacterAndCarriesSummaryWithinConfiguredAndModelLimits() {
        var model = new RecordingModel();
        String source = "earliest fact " + "汉字😀".repeat(200) + " latest fact";
        String result = fold(model, source, 100).block();
        assertEquals(source, String.join("", model.pages));
        assertTrue(model.pages.size() > 2);
        assertEquals("fold-" + model.pages.size(), result);
        assertEquals("", model.previous.get(0));
        assertEquals("fold-1", model.previous.get(1));
        assertTrue(model.inputs.stream().allMatch(tokens -> tokens <= 100));
        assertTrue(model.outputs.stream().allMatch(tokens -> tokens == 16));
        for (String page : model.pages) {
            assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
            assertFalse(Character.isLowSurrogate(page.charAt(0)));
        }
    }

    @Test
    void boundPurposeBudgetAppliesWhenLocalLimitIsLarger() {
        var model = new RecordingModel();
        fold(model, "source".repeat(100), 5000).block();
        assertTrue(model.inputs.stream().allMatch(tokens -> tokens <= 120));
    }

    @Test
    void failingLaterPageCannotReturnPartialReplacement() {
        var model = new RecordingModel();
        model.failOnPage = 3;
        assertThrows(
                IllegalStateException.class, () -> fold(model, "source".repeat(100), 100).block());
        assertEquals(3, model.pages.size());
    }

    @Test
    void promptWithoutRoomFailsBeforeProviderCall() {
        var model = new RecordingModel();
        assertThrows(ContextWindowExceededException.class, () -> fold(model, "hello", 4).block());
        assertTrue(model.pages.isEmpty());
    }

    @Test
    void cancellationDoesNotDispatchRemainingPages() {
        var model = new RecordingModel();
        model.waitOnPage = 2;
        var subscription = fold(model, "source".repeat(100), 100).subscribe();
        subscription.dispose();
        assertEquals(2, model.pages.size());
    }

    private reactor.core.publisher.Mono<String> fold(
            RecordingModel model, String source, int budget) {
        return PurposeTextFold.fold(
                model,
                context,
                source,
                "",
                (previous, page) ->
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(previous + "|PAGE|" + page)
                                        .build()),
                budget,
                2048,
                Duration.ofSeconds(5));
    }

    private static class RecordingModel implements PurposeBindableModel.BoundInvocation {
        final List<String> pages = new ArrayList<>(), previous = new ArrayList<>();
        final List<Long> inputs = new ArrayList<>();
        final List<Integer> outputs = new ArrayList<>();
        int failOnPage, waitOnPage;

        @Override
        public InvocationLimits invocationLimits() {
            return new InvocationLimits(120, 16, Duration.ofSeconds(1), "test");
        }

        @Override
        public String routeVersion() {
            return "test";
        }

        @Override
        public String getModelName() {
            return "fold-test";
        }

        @Override
        public ModelContextProfile resolveContextProfile(List<Msg> messages) {
            return new ModelContextProfile("test", 512, 64, 16);
        }

        @Override
        public ModelContextProfile resolveContextProfile(RuntimeContext context) {
            return resolveContextProfile(List.of());
        }

        @Override
        public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
            return messages.stream().mapToLong(msg -> msg.getTextContent().length() + 10L).sum();
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String text = messages.get(0).getTextContent();
            int separator = text.indexOf("|PAGE|");
            previous.add(text.substring(0, separator));
            pages.add(text.substring(separator + 6));
            inputs.add(estimateInputTokens(messages, tools));
            outputs.add(options.getMaxTokens());
            if (pages.size() == waitOnPage) return Flux.never();
            return Flux.just(
                    ChatResponse.builder()
                            .finishReason(pages.size() == failOnPage ? "length" : "stop")
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("fold-" + pages.size())
                                                    .build()))
                            .build());
        }
    }
}
