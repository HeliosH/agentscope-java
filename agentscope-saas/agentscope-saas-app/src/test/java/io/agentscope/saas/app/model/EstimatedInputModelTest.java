/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.InputTokenAwareModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.saas.app.config.SaasProperties;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class EstimatedInputModelTest {
    @Test
    void admittedImageReachesRealFormatterAndHttpTransportButOversizedRequestDoesNot()
            throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {"id":"response-1","object":"chat.completion","created":1,
                                     "model":"gpt-4.1-mini-2025-04-14",
                                     "choices":[{"index":0,"message":{"role":"assistant","content":"image received"},"finish_reason":"stop"}],
                                     "usage":{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102}}
                                    """));
            // Only the transport endpoint is replaced by a local API fixture; use the production
            // estimator and real formatter/client without contacting the external service.
            Model client =
                    OpenAIChatModel.builder()
                            .apiKey("test-key")
                            .baseUrl(server.url("/v1").toString())
                            .modelName(MODEL)
                            .stream(false)
                            .build();
            Model estimated = EstimatedInputModel.openAi(client, null, MODEL);
            var factory = new ModelRouteFactory();
            var catalog =
                    new ModelCatalog(
                            "vision",
                            List.of(
                                    factory.route(
                                            "vision", "Vision", MODEL, 16000, 1024, 512, true,
                                            estimated)));
            assertTrue(
                    catalog.stream(IMAGE, List.of(), null)
                                    .collectList()
                                    .block(java.time.Duration.ofSeconds(10))
                                    .size()
                            > 0);
            var request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("/v1/chat/completions", request.getPath());
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("image_url"));
            assertTrue(body.contains("https://example.invalid/image.png"));
            assertTrue(body.contains(MODEL));
            assertThrows(
                    ContextWindowExceededException.class,
                    () ->
                            catalog.stream(List.of(IMAGE.get(0), IMAGE.get(0)), List.of(), null)
                                    .blockLast());
            assertEquals(1, server.getRequestCount());
        }
    }

    private static final String MODEL = "gpt-4.1-mini-2025-04-14";
    private static final List<Msg> IMAGE =
            List.of(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    ImageBlock.builder()
                                            .source(
                                                    URLSource.builder()
                                                            .url(
                                                                    "https://example.invalid/image.png")
                                                            .build())
                                            .build())
                            .build());

    @Test
    void productionFactoryAttachesEstimateWithAndWithoutTrafficGovernance() {
        var factory = new ModelRouteFactory();
        for (boolean enabled : List.of(false, true)) {
            var traffic = new SaasProperties.ModelTraffic();
            traffic.setEnabled(enabled);
            Model model =
                    factory.createGovernedRoute(
                            "gateway",
                            "https://api.openai.com/v1",
                            "test-key",
                            MODEL,
                            List.of(),
                            traffic);
            assertInstanceOf(InputTokenAwareModel.class, model);
            var route = factory.route("vision", "Vision", MODEL, 32768, 1024, 512, true, model);
            var catalog = new ModelCatalog("vision", List.of(route));
            long estimate = catalog.estimateInputTokens(IMAGE, List.of());
            assertTrue(estimate >= 9955);
            assertEquals(
                    9955,
                    estimate
                            - TokenCounterUtil.calculateToken(
                                    List.of(Msg.builder().role(MsgRole.USER).build()), List.of()));
        }
    }

    @Test
    void customGatewaysAndUnrecognizedAliasesCannotInheritOfficialCosts() {
        var provider = new CountingModel(false);
        for (String url :
                List.of(
                        "https://proxy.invalid/v1",
                        "https://api.openai.com.evil/v1",
                        "https://api.openai.com/custom",
                        "http://api.openai.com/v1")) {
            assertSame(provider, EstimatedInputModel.openAi(provider, url, MODEL));
        }
        assertSame(provider, EstimatedInputModel.openAi(provider, null, MODEL + "-custom"));
        assertInstanceOf(
                InputTokenAwareModel.class, EstimatedInputModel.openAi(provider, null, MODEL));
    }

    @Test
    void modelAndEndpointOverridesCannotReuseMediaAdmission() {
        var provider = new CountingModel(false);
        Model model = EstimatedInputModel.openAi(provider, null, MODEL);
        for (GenerateOptions options :
                List.of(
                        GenerateOptions.builder().modelName("other").build(),
                        GenerateOptions.builder().baseUrl("https://proxy.invalid/v1").build())) {
            assertThrows(
                    ContextWindowExceededException.class,
                    () -> model.stream(IMAGE, List.of(), options).blockLast());
        }
        assertEquals(0, provider.calls.get());
        model.stream(
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()),
                        List.of(),
                        GenerateOptions.builder().modelName("other").build())
                .blockLast();
        assertEquals(1, provider.calls.get());
    }

    @Test
    void fallbackMaximumRejectsBeforeEitherProviderAndAllowedRequestCanFailOver() {
        var primary = new CountingModel(true);
        var fallback = new CountingModel(false);
        Model estimatedPrimary = EstimatedInputModel.openAi(primary, null, MODEL);
        InputTokenAwareModel estimatedFallback =
                new InputTokenAwareModel() {
                    public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
                        return 16000;
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return fallback.stream(messages, tools, options);
                    }

                    public String getModelName() {
                        return "fallback";
                    }
                };
        ModelRouteFactory factory =
                new ModelRouteFactory() {
                    @Override
                    public Model create(String type, String baseUrl, String apiKey, String name) {
                        return "primary".equals(name) ? estimatedPrimary : estimatedFallback;
                    }
                };
        var endpoint = new SaasProperties.ModelEndpoint();
        endpoint.setName("fallback");
        var traffic = new SaasProperties.ModelTraffic();
        traffic.setEnabled(true);
        Model governed =
                factory.createGovernedRoute(
                        "gateway", null, "test-key", "primary", List.of(endpoint), traffic);
        var small =
                new ModelCatalog(
                        "vision",
                        List.of(
                                factory.route(
                                        "vision", "Vision", MODEL, 14000, 1024, 512, true,
                                        governed)));
        assertThrows(
                ContextWindowExceededException.class,
                () -> small.stream(IMAGE, List.of(), null).blockLast());
        assertEquals(0, primary.calls.get());
        assertEquals(0, fallback.calls.get());
        var large =
                new ModelCatalog(
                        "vision",
                        List.of(
                                factory.route(
                                        "vision", "Vision", MODEL, 32768, 1024, 512, true,
                                        governed)));
        large.stream(IMAGE, List.of(), null).blockLast();
        assertEquals(1, primary.calls.get());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void unknownFallbackMediaCostFailsClosedEvenWhenPrimaryCanEstimate() {
        var primary = new CountingModel(false);
        Model known = EstimatedInputModel.openAi(primary, null, MODEL);
        Model combined =
                EstimatedInputModel.failover(primary, List.of(known, new CountingModel(false)));
        assertThrows(
                ContextWindowExceededException.class,
                () -> ((InputTokenAwareModel) combined).estimateInputTokens(IMAGE, List.of()));
        assertEquals(0, primary.calls.get());
    }

    private static final class CountingModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean fail;

        private CountingModel(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return fail
                    ? Flux.error(new IOException("before first output"))
                    : Flux.just(ChatResponse.builder().content(List.of()).build());
        }

        @Override
        public String getModelName() {
            return MODEL;
        }
    }
}
