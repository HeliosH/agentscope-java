/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ModelCatalogTest {

    @Test
    void routesByMessageMetadataAndCapsOutput() {
        CapturingModel small = new CapturingModel("small-provider");
        CapturingModel large = new CapturingModel("large-provider");
        ModelCatalog catalog =
                new ModelCatalog(
                        "small",
                        List.of(
                                route("small", 8_192, 1_024, small, true),
                                route("large", 32_768, 4_096, large, false)));
        Msg message = selectedMessage("large", "hello");

        catalog.stream(
                        List.of(message),
                        List.of(),
                        GenerateOptions.builder().maxTokens(9_000).build())
                .blockLast();

        assertEquals(0, small.calls.get());
        assertEquals(1, large.calls.get());
        assertEquals(4_096, large.options.get().getMaxTokens());
        RuntimeContext context =
                RuntimeContext.builder().put(ContextWindowAwareModel.MODEL_ID_KEY, "large").build();
        assertEquals(32_768, catalog.resolveContextProfile(context).contextWindowTokens());
    }

    @Test
    void rejectsOversizedInputBeforeProviderCall() {
        CapturingModel provider = new CapturingModel("provider");
        ModelCatalog catalog =
                new ModelCatalog("small", List.of(route("small", 4_096, 1_024, provider, true)));
        Msg oversized = selectedMessage("small", "x".repeat(10_000));

        assertThrows(
                ContextWindowExceededException.class,
                () -> catalog.stream(List.of(oversized), List.of(), null).blockLast());
        assertEquals(0, provider.calls.get());
    }

    private static Msg selectedMessage(String modelId, String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(text)
                .metadata(Map.of(ContextWindowAwareModel.MODEL_ID_KEY, modelId))
                .build();
    }

    private static ModelCatalog.Route route(
            String id, int window, int output, Model model, boolean defaultModel) {
        ModelCatalog.ModelOption option =
                new ModelCatalog.ModelOption(
                        id, id, model.getModelName(), window, output, defaultModel);
        return new ModelCatalog.Route(
                option, new ModelContextProfile(id, window, output, 512), model);
    }

    private static final class CapturingModel implements Model {
        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<GenerateOptions> options = new AtomicReference<>();

        private CapturingModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            this.options.set(options);
            return Flux.just(ChatResponse.builder().content(List.of()).build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }
}
