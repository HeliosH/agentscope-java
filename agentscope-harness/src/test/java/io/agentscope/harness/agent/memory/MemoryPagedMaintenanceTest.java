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
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.InputTokenAwareModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class MemoryPagedMaintenanceTest {
    private final RuntimeContext context = RuntimeContext.empty();
    private final String dailyPath = "memory/" + LocalDate.now() + ".md";

    @Test
    void largeLegacyMemoryAndDailyLedgersAreFoldedBeforeFinalCommit(@TempDir Path workspace)
            throws Exception {
        var model = new WindowModel();
        try (var manager = manager(workspace)) {
            manager.writeUtf8WorkspaceRelative(
                    context, "MEMORY.md", "EARLY_MEMORY " + "known ".repeat(3000));
            manager.writeUtf8WorkspaceRelative(
                    context, dailyPath, "DAILY_START " + "daily ".repeat(6000) + " DAILY_END");
            var consolidator = new MemoryConsolidator(manager, model);
            consolidator.consolidate(context).block();
            assertEquals("- merged memory", manager.readMemoryMd(context));
            assertTrue(consolidator.readWatermark(context).isAfter(Instant.EPOCH));
            assertTrue(model.requests.size() > 2);
            assertTrue(model.requests.get(0).contains("EARLY_MEMORY"));
            assertTrue(model.requests.stream().anyMatch(text -> text.contains("DAILY_END")));
            assertTrue(
                    model.inputs.stream()
                            .allMatch(tokens -> tokens <= model.profile.inputTokenBudget()));
        }
    }

    @Test
    void extractionPagesLargeReferenceAndConversationButAppendsOnlyAfterAllPages(
            @TempDir Path workspace) throws Exception {
        var model = new WindowModel();
        try (var manager = manager(workspace)) {
            String known = "KNOWN_START " + "known ".repeat(3000) + " KNOWN_END";
            manager.writeUtf8WorkspaceRelative(context, "MEMORY.md", known);
            var flush = new MemoryFlushManager(manager, model);
            flush.flushMemories(
                            context,
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent(
                                                    "SOURCE_START "
                                                            + "conversation ".repeat(3000)
                                                            + " SOURCE_END")
                                            .build()))
                    .block();
            assertEquals(known, manager.readMemoryMd(context));
            String daily = manager.readManagedWorkspaceFileUtf8(context, dailyPath);
            assertEquals(1, daily.split("## Memory Flush", -1).length - 1);
            assertTrue(model.requests.stream().anyMatch(text -> text.contains("KNOWN_END")));
            assertTrue(model.requests.stream().anyMatch(text -> text.contains("SOURCE_END")));
            assertTrue(
                    model.inputs.stream()
                            .allMatch(tokens -> tokens <= model.profile.inputTokenBudget()));
        }
    }

    @Test
    void failedLaterConsolidationPageLeavesMemoryAndWatermarkUntouched(@TempDir Path workspace)
            throws Exception {
        var model = new WindowModel();
        model.failOnPage = 2;
        try (var manager = manager(workspace)) {
            manager.writeUtf8WorkspaceRelative(context, "MEMORY.md", "verified facts");
            manager.writeUtf8WorkspaceRelative(context, dailyPath, "daily ".repeat(6000));
            var consolidator = new MemoryConsolidator(manager, model);
            assertThrows(
                    IllegalStateException.class, () -> consolidator.consolidate(context).block());
            assertEquals("verified facts", manager.readMemoryMd(context));
            assertEquals(Instant.EPOCH, consolidator.readWatermark(context));
        }
    }

    private WorkspaceManager manager(Path workspace) {
        return new WorkspaceManager(
                workspace, new RemoteFilesystem(new InMemoryStore(), List.of("paged-memory")));
    }

    private static class WindowModel
            implements Model, ContextWindowAwareModel, InputTokenAwareModel {
        final ModelContextProfile profile = new ModelContextProfile("small", 3072, 512, 128);
        final List<String> requests = new ArrayList<>();
        final List<Long> inputs = new ArrayList<>();
        int failOnPage;

        @Override
        public String getModelName() {
            return "small";
        }

        @Override
        public ModelContextProfile resolveContextProfile(List<Msg> messages) {
            return profile;
        }

        @Override
        public ModelContextProfile resolveContextProfile(RuntimeContext context) {
            return profile;
        }

        @Override
        public long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools) {
            return TokenCounterUtil.calculateToken(messages, tools);
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(
                    messages.stream()
                            .map(Msg::getTextContent)
                            .collect(java.util.stream.Collectors.joining("\n")));
            inputs.add(estimateInputTokens(messages, tools));
            if (requests.size() == failOnPage)
                return Flux.error(new IllegalStateException("injected later page failure"));
            return Flux.just(
                    ChatResponse.builder()
                            .finishReason("stop")
                            .content(List.of(TextBlock.builder().text("- merged memory").build()))
                            .build());
        }
    }
}
