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

    @Test
    void boundRouteKeepsProviderAndLimitsWhenLaterMessagesSelectAnotherModel() {
        CapturingModel small = new CapturingModel("small-provider");
        CapturingModel large = new CapturingModel("large-provider");
        var catalog =
                new ModelCatalog(
                        "small",
                        List.of(
                                route("small", 8192, 1024, small, true),
                                route("large", 32768, 4096, large, false)));
        Model bound =
                catalog.bindToStep(
                        RuntimeContext.empty(), List.of(selectedMessage("small", "hello")));
        bound.stream(
                        List.of(selectedMessage("large", "hello")),
                        List.of(),
                        GenerateOptions.builder().maxTokens(9000).build())
                .blockLast();
        assertEquals(1, small.calls.get());
        assertEquals(0, large.calls.get());
        assertEquals(1024, small.options.get().getMaxTokens());
        assertEquals(
                "small",
                ((ContextWindowAwareModel) bound)
                        .resolveContextProfile(RuntimeContext.empty())
                        .modelId());
    }

    @Test
    void catalogRefreshAffectsNextBindingButNotExistingStep() {
        var orgId = java.util.UUID.randomUUID();
        var definitions =
                org.mockito.Mockito.mock(
                        io.agentscope.saas.domain.repository.ModelDefinitionRepository.class);
        var cipher = org.mockito.Mockito.mock(ModelCredentialCipher.class);
        var factory = org.mockito.Mockito.mock(ModelRouteFactory.class);
        var definition =
                org.mockito.Mockito.mock(
                        io.agentscope.saas.domain.model.ModelDefinitionEntity.class);
        org.mockito.Mockito.when(definition.getModelId()).thenReturn("small");
        org.mockito.Mockito.when(definition.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(definition.isDefaultModel()).thenReturn(true);
        org.mockito.Mockito.when(definitions.findByOrgIdOrderByModelId(orgId))
                .thenReturn(List.of(definition));
        var oldProvider = new CapturingModel("old");
        var newProvider = new CapturingModel("new");
        org.mockito.Mockito.when(factory.managedRoute(definition, null, null))
                .thenReturn(
                        route("small", 8192, 1024, oldProvider, true),
                        route("small", 16384, 2048, newProvider, true));
        var catalog =
                new ModelCatalog(
                        "small",
                        List.of(route("small", 8192, 1024, oldProvider, true)),
                        definitions,
                        cipher,
                        factory,
                        null);
        var messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("hello")
                                .metadata(
                                        Map.of(
                                                ModelCatalog.ORG_ID_KEY,
                                                orgId.toString(),
                                                ContextWindowAwareModel.MODEL_ID_KEY,
                                                "small"))
                                .build());
        Model pinned = catalog.bindToStep(RuntimeContext.empty(), messages);
        catalog.refresh(orgId);
        pinned.stream(messages, List.of(), null).blockLast();
        Model next = catalog.bindToStep(RuntimeContext.empty(), messages);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ((io.agentscope.core.model.StepBindableModel.BoundModel) pinned).routeVersion(),
                ((io.agentscope.core.model.StepBindableModel.BoundModel) next).routeVersion());
        next.stream(messages, List.of(), null).blockLast();
        assertEquals(1, oldProvider.calls.get());
        assertEquals(1, newProvider.calls.get());
        assertEquals(
                8192,
                ((ContextWindowAwareModel) pinned)
                        .resolveContextProfile(messages)
                        .contextWindowTokens());
    }

    @Test
    void configuredMediaEstimatorGovernsAdmissionAndSurvivesBinding() {
        var provider = new CapturingModel("vision");
        var base = route("vision", 4096, 1024, provider, true);
        var route =
                new ModelCatalog.Route(
                        base.option(),
                        base.contextProfile(),
                        provider,
                        (messages, tools) ->
                                io.agentscope.harness.agent.memory.compaction.TokenCounterUtil
                                        .calculateToken(messages, tools, block -> 1500));
        var catalog = new ModelCatalog("vision", List.of(route));
        var image =
                io.agentscope.core.message.ImageBlock.builder()
                        .source(
                                io.agentscope.core.message.URLSource.builder()
                                        .url("https://example.invalid/image.png")
                                        .build())
                        .build();
        var message = Msg.builder().role(MsgRole.USER).content(image).build();
        var bound = catalog.bindToStep(RuntimeContext.empty(), List.of(message));
        assertEquals(
                catalog.estimateInputTokens(List.of(message), List.of()),
                ((io.agentscope.core.model.InputTokenAwareModel) bound)
                        .estimateInputTokens(List.of(message), List.of()));
        bound.stream(List.of(message), List.of(), null).blockLast();
        assertEquals(1, provider.calls.get());
        assertThrows(
                ContextWindowExceededException.class,
                () -> bound.stream(List.of(message, message), List.of(), null).blockLast());
        assertEquals(1, provider.calls.get());
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
