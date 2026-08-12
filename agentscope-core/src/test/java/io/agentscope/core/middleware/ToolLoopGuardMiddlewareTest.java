/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ToolLoopGuardMiddlewareTest {

    @Test
    void blocksFourthEquivalentToolCallWithinWindow() {
        ToolLoopGuardMiddleware guard = new ToolLoopGuardMiddleware(4, 8);
        RuntimeContext context = RuntimeContext.empty();
        AtomicInteger executions = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            ActingInput input =
                    new ActingInput(
                            List.of(
                                    new ToolUseBlock(
                                            "call-" + i,
                                            "read_file",
                                            Map.of("path", "report.txt"))));
            StepVerifier.create(
                            guard.onActing(
                                    null,
                                    context,
                                    input,
                                    ignored -> {
                                        executions.incrementAndGet();
                                        return Flux.empty();
                                    }))
                    .verifyComplete();
        }

        ActingInput fourth =
                new ActingInput(
                        List.of(
                                new ToolUseBlock(
                                        "call-4", "read_file", Map.of("path", "report.txt"))));
        StepVerifier.create(guard.onActing(null, context, fourth, ignored -> Flux.empty()))
                .expectError(ToolLoopGuardMiddleware.ToolLoopDetectedException.class)
                .verify();
        assertEquals(3, executions.get());
    }

    @Test
    void canonicalFingerprintIgnoresMapOrderAndCallId() {
        ToolUseBlock first =
                new ToolUseBlock("one", "execute", Map.of("command", "pwd", "timeout", 5));
        java.util.LinkedHashMap<String, Object> reversed = new java.util.LinkedHashMap<>();
        reversed.put("timeout", 5);
        reversed.put("command", "pwd");
        ToolUseBlock second = new ToolUseBlock("two", "execute", reversed);

        assertEquals(
                ToolLoopGuardMiddleware.fingerprint(first),
                ToolLoopGuardMiddleware.fingerprint(second));
    }
}
