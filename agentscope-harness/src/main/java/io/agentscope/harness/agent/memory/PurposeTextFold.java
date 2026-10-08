/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.PurposeBindableModel;
import io.agentscope.core.model.StepBindableModel;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/** Ordered, budget-aware text folding. A failed page never exposes a partial replacement. */
public final class PurposeTextFold {
    private PurposeTextFold() {}

    private record State(int offset, String summary) {}

    public static Model bind(
            Model model, RuntimeContext context, PurposeBindableModel.Purpose purpose) {
        return model instanceof PurposeBindableModel router
                ? router.bindToPurpose(context, purpose)
                : model instanceof StepBindableModel router ? router.bindToContext(context) : model;
    }

    public static int inputBudget(Model bound, RuntimeContext context, int configured) {
        int budget = configured > 0 ? configured : Integer.MAX_VALUE;
        if (bound instanceof ContextWindowAwareModel aware)
            budget = Math.min(budget, aware.resolveContextProfile(context).inputTokenBudget());
        if (bound instanceof PurposeBindableModel.BoundInvocation invocation)
            budget = Math.min(budget, invocation.invocationLimits().inputTokens());
        return budget;
    }

    public static Mono<String> fold(
            Model bound,
            RuntimeContext context,
            String source,
            String initialSummary,
            BiFunction<String, String, List<Msg>> request,
            int configuredInputTokens,
            int outputTokens,
            Duration timeout) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(initialSummary, "initialSummary");
        return Mono.defer(
                () -> {
                    int budget = inputBudget(bound, context, configuredInputTokens);
                    return Mono.just(new State(0, initialSummary))
                            .expand(
                                    state -> {
                                        if (state.offset() == source.length()) return Mono.empty();
                                        int end = pageEnd(bound, source, state, request, budget);
                                        return invoke(
                                                        bound,
                                                        context,
                                                        request.apply(
                                                                state.summary(),
                                                                source.substring(
                                                                        state.offset(), end)),
                                                        outputTokens,
                                                        timeout)
                                                .map(summary -> new State(end, summary));
                                    })
                            .last()
                            .map(State::summary);
                });
    }

    private static int pageEnd(
            Model model,
            String source,
            State state,
            BiFunction<String, String, List<Msg>> request,
            int budget) {
        // Bound candidate allocation even when the source contains millions of characters.
        int high =
                (int)
                        Math.min(
                                source.length(),
                                state.offset() + Math.min(Integer.MAX_VALUE, (long) budget * 4));
        int low = state.offset(), best = low;
        while (low <= high) {
            int end = low + (high - low) / 2;
            if (TokenCounterUtil.calculateToken(
                            request.apply(state.summary(), source.substring(state.offset(), end)),
                            null,
                            model)
                    <= budget) {
                best = end;
                low = end + 1;
            } else high = end - 1;
        }
        if (best < source.length()
                && best > state.offset()
                && Character.isHighSurrogate(source.charAt(best - 1))
                && Character.isLowSurrogate(source.charAt(best))) best--;
        if (best == state.offset())
            throw new ContextWindowExceededException(
                    "Auxiliary prompt and previous summary leave no room for the next page");
        return best;
    }

    public static Mono<String> invoke(
            Model bound,
            RuntimeContext context,
            List<Msg> messages,
            int requestedOutput,
            Duration timeout) {
        return Mono.defer(
                () -> {
                    if (requestedOutput < 1
                            || timeout == null
                            || timeout.isNegative()
                            || timeout.isZero())
                        return Mono.error(
                                new IllegalArgumentException(
                                        "Positive output and timeout required"));
                    int output = requestedOutput;
                    Duration deadline = timeout;
                    if (bound instanceof ContextWindowAwareModel aware)
                        output =
                                Math.min(
                                        output,
                                        aware.resolveContextProfile(context).maxOutputTokens());
                    if (bound instanceof PurposeBindableModel.BoundInvocation invocation) {
                        output = Math.min(output, invocation.invocationLimits().outputTokens());
                        if (invocation.invocationLimits().timeout().compareTo(deadline) < 0)
                            deadline = invocation.invocationLimits().timeout();
                    }
                    if (TokenCounterUtil.calculateToken(messages, null, bound)
                            > inputBudget(bound, context, 0))
                        return Mono.error(
                                new ContextWindowExceededException(
                                        "Auxiliary input exceeds its budget"));
                    return bound.stream(
                                    messages,
                                    null,
                                    GenerateOptions.builder().maxTokens(output).build())
                            .reduce(
                                    new StringBuilder(),
                                    (text, response) -> {
                                        if (List.of("length", "max_tokens", "content_filter")
                                                .contains(
                                                        response.getFinishReason() == null
                                                                ? ""
                                                                : response.getFinishReason()))
                                            throw new IllegalStateException(
                                                    "Incomplete auxiliary output: "
                                                            + response.getFinishReason());
                                        if (response.getContent() != null)
                                            for (var block : response.getContent())
                                                if (block instanceof TextBlock tb
                                                        && tb.getText() != null) {
                                                    if (text.length() + (long) tb.getText().length()
                                                            > 1024 * 1024)
                                                        throw new IllegalStateException(
                                                                "Auxiliary output exceeds"
                                                                        + " collection limit");
                                                    text.append(tb.getText());
                                                }
                                        return text;
                                    })
                            .timeout(deadline)
                            .map(StringBuilder::toString)
                            .map(String::strip)
                            .filter(text -> !text.isBlank())
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Auxiliary model returned no text")));
                });
    }
}
