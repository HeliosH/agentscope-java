/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceContextBudgetTest {

    @TempDir Path workspace;

    @Test
    void largeKnowledgeAndMemoryFitWholeSectionIncludingMarkup() throws Exception {
        Files.writeString(
                workspace.resolve("AGENTS.md"), "Always preserve confirmed requirements.");
        Files.writeString(workspace.resolve("MEMORY.md"), "记忆🧠\n".repeat(10000));
        Files.createDirectory(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/KNOWLEDGE.md"), "知识条目\n".repeat(10000));
        try (var manager = new WorkspaceManager(workspace)) {
            var middleware = new WorkspaceContextMiddleware(manager, 2000);
            String section = middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block();
            assertTrue(TokenCounterUtil.calculateTextToken(section) <= 2000);
            assertTrue(section.contains("Always preserve confirmed requirements."));
            assertTrue(section.contains("</loaded_context>"));
            assertFalse(section.contains("\ufffd"));
        }
    }

    @Test
    void requiredInstructionsCannotBeSilentlyTruncated() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "mandatory instruction\n".repeat(1000));
        try (var manager = new WorkspaceManager(workspace)) {
            var middleware = new WorkspaceContextMiddleware(manager, 1500);
            assertThrows(
                    ContextWindowExceededException.class,
                    () -> middleware.onSystemPrompt(null, null, "").block());
        }
    }

    @Test
    void additionalInstructionsAndZeroRemainingBudgetAreChecked() throws Exception {
        Files.writeString(workspace.resolve("policy.md"), "required policy\n".repeat(1000));
        try (var manager = new WorkspaceManager(workspace)) {
            var middleware = new WorkspaceContextMiddleware(manager, 1500);
            middleware.setAdditionalContextFiles(List.of("policy.md"));
            assertThrows(
                    ContextWindowExceededException.class,
                    () -> middleware.onSystemPrompt(null, null, "").block());
            var zero = new WorkspaceContextMiddleware(manager, 0);
            assertThrows(
                    ContextWindowExceededException.class,
                    () -> zero.onSystemPrompt(null, null, "").block());
        }
    }
}
