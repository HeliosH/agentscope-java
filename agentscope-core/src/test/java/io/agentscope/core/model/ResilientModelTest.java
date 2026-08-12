/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.exception.InternalServerException;
import io.agentscope.core.model.exception.RateLimitException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ResilientModelTest {

    @Test
    void failsOverOnTransientErrorBeforeFirstOutput() {
        FakeModel primary =
                new FakeModel(
                        "primary", () -> Flux.error(new RateLimitException("limited", null, null)));
        ChatResponse expected = ChatResponse.builder().id("fallback-response").build();
        FakeModel fallback = new FakeModel("fallback", () -> Flux.just(expected));
        ResilientModel model = new ResilientModel(List.of(primary, fallback), policy());

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNext(expected)
                .verifyComplete();
        assertEquals(1, primary.calls.get());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void failsOverOnProviderServerError() {
        FakeModel primary =
                new FakeModel(
                        "primary",
                        () ->
                                Flux.error(
                                        new InternalServerException(
                                                "unavailable", 503, null, null)));
        ChatResponse expected = ChatResponse.builder().id("server-fallback").build();
        FakeModel fallback = new FakeModel("fallback", () -> Flux.just(expected));
        ResilientModel model = new ResilientModel(List.of(primary, fallback), policy());

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void doesNotFailOverAfterPartialStreamOutput() {
        ChatResponse partial = ChatResponse.builder().id("partial").build();
        FakeModel primary =
                new FakeModel(
                        "primary",
                        () ->
                                Flux.concat(
                                        Flux.just(partial),
                                        Flux.error(new RateLimitException("limited", null, null))));
        FakeModel fallback =
                new FakeModel(
                        "fallback", () -> Flux.just(ChatResponse.builder().id("bad").build()));
        ResilientModel model = new ResilientModel(List.of(primary, fallback), policy());

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNext(partial)
                .expectError(RateLimitException.class)
                .verify();
        assertEquals(0, fallback.calls.get());
    }

    @Test
    void failsOverWhenPrimaryCapacityCannotBeAcquired() throws Exception {
        FakeModel primary = new FakeModel("primary", Flux::never);
        ChatResponse expected = ChatResponse.builder().id("capacity-fallback").build();
        FakeModel fallback = new FakeModel("fallback", () -> Flux.just(expected));
        ResilientModel.Policy constrained =
                new ResilientModel.Policy(
                        1, 0, Duration.ofMillis(100), Duration.ZERO, 3, Duration.ofSeconds(1));
        ResilientModel model = new ResilientModel(List.of(primary, fallback), constrained);
        Disposable first = model.stream(List.of(), List.of(), null).subscribe();
        try {
            Thread.sleep(30);
            StepVerifier.create(model.stream(List.of(), List.of(), null))
                    .expectNext(expected)
                    .verifyComplete();
        } finally {
            first.dispose();
        }
        assertEquals(1, fallback.calls.get());
    }

    private static ResilientModel.Policy policy() {
        return new ResilientModel.Policy(
                2, 0, Duration.ofSeconds(1), Duration.ZERO, 3, Duration.ofSeconds(1));
    }

    private static final class FakeModel implements Model {
        private final String name;
        private final java.util.function.Supplier<Flux<ChatResponse>> response;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeModel(String name, java.util.function.Supplier<Flux<ChatResponse>> response) {
            this.name = name;
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return response.get();
        }

        @Override
        public String getModelName() {
            return name;
        }
    }
}
