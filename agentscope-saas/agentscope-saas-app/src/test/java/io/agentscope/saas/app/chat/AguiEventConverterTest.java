/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the framework {@code AgentEvent} → AG-UI event mapping used by the chat endpoint. */
class AguiEventConverterTest {

    @Test
    void mapsTextBlockLifecycleToAguiTextMessageEvents() {
        AguiEventConverter converter = new AguiEventConverter("thread-1", "run-1");
        List<AguiEvent> events = new ArrayList<>();

        events.add(converter.runStarted());
        events.addAll(converter.convert(new TextBlockStartEvent("reply-1", "block-1")));
        events.addAll(converter.convert(new TextBlockDeltaEvent("reply-1", "block-1", "Hello")));
        events.addAll(converter.convert(new TextBlockDeltaEvent("reply-1", "block-1", " world")));
        events.addAll(converter.convert(new TextBlockEndEvent("reply-1", "block-1")));
        events.addAll(converter.runFinished());

        // RUN_STARTED, TEXT_MESSAGE_START, 2x CONTENT, TEXT_MESSAGE_END, RUN_FINISHED
        assertEquals(6, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(1));
        assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(2));
        assertEquals("Hello", ((AguiEvent.TextMessageContent) events.get(2)).delta());
        assertInstanceOf(AguiEvent.TextMessageContent.class, events.get(3));
        assertEquals(" world", ((AguiEvent.TextMessageContent) events.get(3)).delta());
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(4));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(5));
    }

    @Test
    void runFinishedClosesOpenTextMessage() {
        AguiEventConverter converter = new AguiEventConverter("t", "r");
        List<AguiEvent> events = new ArrayList<>();
        // Open a text message but never explicitly end it.
        events.addAll(converter.convert(new TextBlockStartEvent("reply", "blk")));
        events.addAll(converter.convert(new TextBlockDeltaEvent("reply", "blk", "partial")));
        events.addAll(converter.runFinished());

        // Finishing must auto-close: ... TEXT_MESSAGE_END then RUN_FINISHED last.
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
        assertTrue(
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd),
                "expected an auto-emitted TEXT_MESSAGE_END");
    }

    @Test
    void mapsRequireUserConfirmToCustomEvent() {
        AguiEventConverter converter = new AguiEventConverter("thread-1", "run-1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("writeFile")
                        .input(Map.of("path", "notes.md", "content", "hi"))
                        .build();
        List<AguiEvent> events =
                converter.convert(new RequireUserConfirmEvent("reply-1", List.of(toolCall)));

        assertEquals(1, events.size());
        AguiEvent.Custom custom = assertInstanceOf(AguiEvent.Custom.class, events.get(0));
        assertEquals("require_user_confirm", custom.name());
        assertEquals("thread-1", custom.threadId());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) custom.value();
        assertEquals("reply-1", value.get("replyId"));
        @SuppressWarnings("unchecked")
        List<ToolUseBlock> toolCalls = (List<ToolUseBlock>) value.get("toolCalls");
        assertEquals(1, toolCalls.size());
        assertEquals("writeFile", toolCalls.get(0).getName());
    }
}
