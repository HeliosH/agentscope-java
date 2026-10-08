/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.PurposeBindableModel;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/** Bounded helper invocation; failed or truncated output must never become curated memory. */
final class MemoryModelInvocation {
    private MemoryModelInvocation() {}

    static Mono<String> invoke(
            Model model,
            RuntimeContext context,
            List<Msg> messages,
            int maxTokens,
            Duration timeout) {
        return invoke(
                model,
                context,
                messages,
                maxTokens,
                timeout,
                PurposeBindableModel.Purpose.MEMORY_EXTRACT);
    }

    static Mono<String> invoke(
            Model model,
            RuntimeContext context,
            List<Msg> messages,
            int maxTokens,
            Duration timeout,
            PurposeBindableModel.Purpose purpose) {
        return Mono.defer(
                () ->
                        PurposeTextFold.invoke(
                                PurposeTextFold.bind(model, context, purpose),
                                context,
                                messages,
                                maxTokens,
                                timeout));
    }
}
