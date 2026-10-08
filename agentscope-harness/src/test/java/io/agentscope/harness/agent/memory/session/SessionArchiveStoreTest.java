/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionArchiveStoreTest {
    @Test
    void canonicalDigestIgnoresRoutingTimestampAndMapOrdering() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1.0);
        second.put("b", 2);
        Msg a = tool(first, "2026-10-06T00:00:00Z", Map.of("modelId", "a"));
        Msg b = tool(second, "2026-10-06T01:00:00Z", Map.of("modelId", "b"));
        assertEquals(SessionArchiveStore.digest(a), SessionArchiveStore.digest(b));
    }

    @Test
    void digestIgnoresToolLifecycleButStillDetectsChangedArguments() {
        Msg source = tool(Map.of("state", "business-value"), "2026-10-06T00:00:00Z", Map.of());
        ToolUseBlock call = source.getContentBlocks(ToolUseBlock.class).get(0);
        for (ToolCallState state : ToolCallState.values()) {
            Msg updated = source.withContent(List.of(call.withState(state)));
            assertEquals(SessionArchiveStore.digest(source), SessionArchiveStore.digest(updated));
        }
        Msg changed = source.withContent(List.of(call.withInput(Map.of("state", "changed"))));
        assertNotEquals(SessionArchiveStore.digest(source), SessionArchiveStore.digest(changed));
    }

    @Test
    void payloadRoundTripsStructuredBlocksWithoutRoutingMetadata() {
        Msg source =
                tool(
                        Map.of("command", "echo hello"),
                        "2026-10-06T00:00:00Z",
                        Map.of("orgId", "private"));
        Msg restored =
                JsonUtils.getJsonCodec().fromJson(SessionArchiveStore.payload(source), Msg.class);
        assertTrue(restored.getMetadata().isEmpty());
        assertInstanceOf(ToolUseBlock.class, restored.getContent().get(0));
        assertEquals(
                "echo hello",
                ((ToolUseBlock) restored.getContent().get(0)).getInput().get("command"));
        assertEquals(SessionArchiveStore.digest(source), SessionArchiveStore.digest(restored));
    }

    @Test
    void genuineUserTextContainingContextTagsIsNotDiscarded() {
        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("Explain <session_context> in this document")
                        .build();
        assertTrue(SessionArchiveStore.sourceMessage(user));
    }

    @Test
    void chainedProjectionKeepsOriginalDigestAndRejectsChangedIdentity() {
        Msg source = Msg.builder().id("a").role(MsgRole.USER).textContent("full").build();
        Msg first =
                SessionArchiveStore.projection(
                        source,
                        source.withContent(List.of(TextBlock.builder().text("short").build())));
        Msg next =
                SessionArchiveStore.projection(
                        first,
                        first.withContent(List.of(TextBlock.builder().text("shorter").build())));
        assertEquals(
                SessionArchiveStore.digest(source),
                next.getMetadata().get(SessionArchiveStore.PROJECTION_SOURCE_HASH));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionArchiveStore.projection(
                                source,
                                Msg.builder()
                                        .id("other")
                                        .role(MsgRole.USER)
                                        .textContent("short")
                                        .build()));
    }

    private static Msg tool(Map<String, Object> input, String time, Map<String, Object> metadata) {
        return Msg.builder()
                .id("a")
                .role(MsgRole.ASSISTANT)
                .timestamp(time)
                .metadata(metadata)
                .content(ToolUseBlock.builder().id("call").name("execute").input(input).build())
                .build();
    }
}
