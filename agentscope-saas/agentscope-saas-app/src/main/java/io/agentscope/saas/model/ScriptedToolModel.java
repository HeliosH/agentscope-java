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
package io.agentscope.saas.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * A scripted, zero-dependency model used by the {@code dev} profile to drive the ReAct agent into
 * executing shell commands in a sandbox <em>without</em> a real LLM. It interprets the user's chat
 * message literally as the shell command to run.
 *
 * <p>ReAct loop interaction (see {@code ReActAgent#isFinished}): the loop continues as long as the
 * assistant message carries a {@link ToolUseBlock}. So this model emits:
 *
 * <ol>
 *   <li><b>First call</b> (no tool result in history yet) — a single {@link ToolUseBlock} named
 *       {@code "execute"} (the registered {@code ShellExecuteTool} name) with {@code command} set to
 *       the last user message. The agent then runs that command in the sandbox.
 *   <li><b>Second call</b> (a {@link ToolResultBlock} is now present) — a text-only response
 *       summarizing the tool output, so {@code isFinished} returns true and the loop ends.
 * </ol>
 *
 * <p>This mirrors the {@code MockModel.createToolCallResponse} pattern from the core test fixtures.
 */
public class ScriptedToolModel implements Model {

    /** Registered name of {@code io.agentscope.harness.agent.tool.ShellExecuteTool}. */
    private static final String SHELL_TOOL_NAME = "execute";

    private static final String MODEL_NAME = "scripted-tool";

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String lastToolOutput = findLastToolOutput(messages);
        if (lastToolOutput != null) {
            // Second iteration: tool already executed — terminate the loop with a text reply.
            String summary =
                    "Command executed in sandbox. Output:\n" + truncate(lastToolOutput, 4000);
            return Flux.just(textResponse(summary));
        }

        String command = findLastUserText(messages);
        if (command == null || command.isBlank()) {
            return Flux.just(textResponse("No command provided."));
        }
        return Flux.just(toolCallResponse(command));
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    private static ChatResponse toolCallResponse(String command) {
        Map<String, Object> args = new HashMap<>();
        args.put("command", command);
        args.put("working_directory", "");
        args.put("timeout", 30);
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(SHELL_TOOL_NAME)
                                        .id("tool_call_" + UUID.randomUUID())
                                        .input(args)
                                        .content(JsonUtils.getJsonCodec().toJson(args))
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    /** Returns the text of the last {@link MsgRole#USER} message, or {@code null} if none. */
    private static String findLastUserText(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    /**
     * Returns the concatenated text output of the most recent {@link ToolResultBlock} in history, or
     * {@code null} if no tool has run yet. Presence of a tool result signals the second ReAct
     * iteration.
     */
    private static String findLastToolOutput(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<ToolResultBlock> results = messages.get(i).getContentBlocks(ToolResultBlock.class);
            if (!results.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ToolResultBlock result : results) {
                    for (ContentBlock block : result.getOutput()) {
                        if (block instanceof TextBlock tb) {
                            if (!sb.isEmpty()) {
                                sb.append('\n');
                            }
                            sb.append(tb.getText());
                        }
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
