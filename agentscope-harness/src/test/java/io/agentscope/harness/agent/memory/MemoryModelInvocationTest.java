/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class MemoryModelInvocationTest {
    @Test
    void capsRequestedOutputTokens() {
        var tokens = new AtomicInteger();
        var model =
                model(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("NO_REPLY")
                                                                .build()))
                                        .build()),
                        tokens);
        assertEquals(
                "NO_REPLY",
                MemoryModelInvocation.invoke(
                                model,
                                RuntimeContext.empty(),
                                List.of(),
                                1024,
                                Duration.ofSeconds(1))
                        .block());
        assertEquals(1024, tokens.get());
    }

    @Test
    void totalDeadlineCancelsNeverEndingHelper() {
        var error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                MemoryModelInvocation.invoke(
                                                model(Flux.never(), new AtomicInteger()),
                                                RuntimeContext.empty(),
                                                List.of(),
                                                1024,
                                                Duration.ofMillis(25))
                                        .block(Duration.ofSeconds(2)));
        assertTrue(error.getCause() instanceof TimeoutException);
    }

    @Test
    void truncatedConsolidationDoesNotOverwriteMemoryOrAdvanceWatermark(@TempDir Path workspace)
            throws Exception {
        var context = RuntimeContext.empty();
        var filesystem = new RemoteFilesystem(new InMemoryStore(), List.of("memory"));
        try (var manager = new WorkspaceManager(workspace, filesystem)) {
            manager.writeUtf8WorkspaceRelative(context, "MEMORY.md", "verified memory");
            manager.writeUtf8WorkspaceRelative(context, "memory/2026-09-30.md", "new preference");
            var response =
                    ChatResponse.builder()
                            .finishReason("length")
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("partial replacement")
                                                    .build()))
                            .build();
            var consolidator =
                    new MemoryConsolidator(
                            manager, model(Flux.just(response), new AtomicInteger()));
            assertThrows(
                    IllegalStateException.class, () -> consolidator.consolidate(context).block());
            assertEquals("verified memory", manager.readMemoryMd(context));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(context));
            var flush =
                    new MemoryFlushManager(
                            manager, model(Flux.just(response), new AtomicInteger()));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            flush.flushMemories(
                                            context,
                                            List.of(
                                                    Msg.builder()
                                                            .role(MsgRole.USER)
                                                            .textContent("remember this")
                                                            .build()))
                                    .block());
            assertEquals("verified memory", manager.readMemoryMd(context));
        }
    }

    private static Model model(Flux<ChatResponse> responses, AtomicInteger outputTokens) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                outputTokens.set(options.getMaxTokens());
                return responses;
            }

            @Override
            public String getModelName() {
                return "memory-test";
            }
        };
    }
}
