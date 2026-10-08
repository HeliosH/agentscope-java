/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryOffloadIdempotencyTest {
    @TempDir Path workspace;

    @Test
    void repeatedOffloadOnlyAppendsNewMessageIds() throws Exception {
        try (var manager = new WorkspaceManager(workspace)) {
            var flush = new MemoryFlushManager(manager, null);
            var context = RuntimeContext.empty();
            var first =
                    Msg.builder().id("message-1").role(MsgRole.USER).textContent("hello").build();
            var second =
                    Msg.builder()
                            .id("message-2")
                            .role(MsgRole.ASSISTANT)
                            .textContent("hello")
                            .build();
            flush.offloadMessages(context, List.of(first), "agent", "session");
            flush.offloadMessages(context, List.of(first, second), "agent", "session");
            flush.offloadMessages(context, List.of(first, second), "agent", "session");
            var file = manager.resolveSessionContextFile(context, "agent", "session");
            assertEquals(2, Files.readAllLines(file).size());
            var tree = new SessionTree(file, workspace, null);
            tree.load();
            assertEquals(2, tree.size());
        }
    }

    @Test
    void sameIdWithDifferentContentCannotReplaceArchivedMessage() {
        var tree = new SessionTree(workspace.resolve("history.jsonl"), workspace, null);
        tree.append(new SessionEntry.MessageEntry("id", null, null, "USER", "original", null));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tree.append(
                                new SessionEntry.MessageEntry(
                                        "id", null, null, "USER", "changed", null)));
        assertEquals(1, tree.size());
    }
}
