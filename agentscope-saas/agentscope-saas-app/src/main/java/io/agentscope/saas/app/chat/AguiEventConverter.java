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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts framework {@link AgentEvent}s (from {@code ReActAgent.streamEvents}) into AG-UI protocol
 * {@link AguiEvent}s, producing a wire stream that matches the 18 AG-UI event types consumed by the
 * frontend ({@code docs/enterprise-platform-java/11-frontend-migration.md}).
 *
 * <p>This mirrors the framework's {@code AguiAgentAdapter} but is invoked on a stream produced via
 * {@code streamEvents(msgs, runtimeContext)}, allowing the tenant context to be injected before the
 * agent runs (the built-in adapter calls {@code agent.stream()} with no RuntimeContext hook).
 *
 * <p>The instance is stateful for the duration of a single run; create a new one per request.
 */
public class AguiEventConverter {

    private final String threadId;
    private final String runId;

    private boolean textOpen = false;
    private String textMessageId;

    public AguiEventConverter(String threadId, String runId) {
        this.threadId = threadId;
        this.runId = runId;
    }

    /** Emit the AG-UI run-started event. */
    public AguiEvent runStarted() {
        return new AguiEvent.RunStarted(threadId, runId);
    }

    /** Emit the AG-UI run-finished event, closing any open text message first. */
    public List<AguiEvent> runFinished() {
        List<AguiEvent> out = new ArrayList<>();
        closeTextIfOpen(out);
        out.add(new AguiEvent.RunFinished(threadId, runId));
        return out;
    }

    /**
     * Convert a single framework event into zero or more AG-UI events.
     *
     * @param event a framework agent event
     * @return AG-UI events to forward (possibly empty)
     */
    public List<AguiEvent> convert(AgentEvent event) {
        List<AguiEvent> out = new ArrayList<>();
        if (event instanceof TextBlockStartEvent e) {
            // Open a text message keyed by the framework blockId/replyId.
            textMessageId = e.getBlockId() != null ? e.getBlockId() : e.getReplyId();
            out.add(new AguiEvent.TextMessageStart(threadId, runId, textMessageId, "assistant"));
            textOpen = true;
        } else if (event instanceof TextBlockDeltaEvent e) {
            ensureTextOpen(out, e.getBlockId() != null ? e.getBlockId() : e.getReplyId());
            if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                out.add(
                        new AguiEvent.TextMessageContent(
                                threadId, runId, textMessageId, e.getDelta()));
            }
        } else if (event instanceof TextBlockEndEvent) {
            closeTextIfOpen(out);
        } else if (event instanceof ToolCallStartEvent e) {
            // A tool call interrupts text; close any open text message first.
            closeTextIfOpen(out);
            out.add(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, e.getToolCallId(), e.getToolCallName()));
        } else if (event instanceof ToolCallEndEvent e) {
            out.add(new AguiEvent.ToolCallEnd(threadId, runId, e.getToolCallId()));
        } else if (event instanceof ToolResultStartEvent) {
            // No AG-UI event needed at result start; result content is delivered on end/delta.
            // (Intentionally left as a no-op to keep the wire stream lean.)
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                out.add(
                        new AguiEvent.ToolCallResult(
                                threadId,
                                runId,
                                e.getToolCallId(),
                                e.getDelta(),
                                "tool",
                                e.getToolCallId()));
            }
        } else if (event instanceof ToolResultEndEvent) {
            // Tool result completion is implied by the next text message / run finish.
        }
        // Other event types (thinking, data blocks, hints, confirms) are not mapped in Phase 1.
        return out;
    }

    private void ensureTextOpen(List<AguiEvent> out, String messageId) {
        if (!textOpen) {
            textMessageId = messageId != null ? messageId : runId;
            out.add(new AguiEvent.TextMessageStart(threadId, runId, textMessageId, "assistant"));
            textOpen = true;
        }
    }

    private void closeTextIfOpen(List<AguiEvent> out) {
        if (textOpen) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, textMessageId));
            textOpen = false;
            textMessageId = null;
        }
    }
}
