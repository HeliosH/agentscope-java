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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.InputTokenAwareModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.function.ToIntFunction;

/** Text estimation with explicit provider media costs and saturating arithmetic. */
public final class TokenCounterUtil {
    private TokenCounterUtil() {}

    private static final ToIntFunction<ContentBlock> REQUIRE_MEDIA_ESTIMATOR =
            block -> {
                throw new ContextWindowExceededException(
                        "Token cost is unknown for "
                                + block.getClass().getSimpleName()
                                + "; configure a provider-specific media token estimator before"
                                + " submitting this input.");
            };

    public static int calculateToken(List<Msg> messages) {
        return calculateToken(messages, null);
    }

    public static int calculateToken(List<Msg> messages, List<ToolSchema> tools) {
        return calculateToken(messages, tools, REQUIRE_MEDIA_ESTIMATOR);
    }

    /** Uses a provider estimator when present; rejects invalid negative estimates. */
    public static int calculateToken(List<Msg> messages, List<ToolSchema> tools, Model model) {
        if (model instanceof InputTokenAwareModel aware) {
            long estimate = aware.estimateInputTokens(messages, tools);
            if (estimate < 0)
                throw new IllegalArgumentException("Input token estimate must not be negative");
            return cap(estimate);
        }
        return calculateToken(messages, tools);
    }

    /** Media costs are supplied by trusted model configuration, never inferred from a URL. */
    public static int calculateToken(
            List<Msg> messages,
            List<ToolSchema> tools,
            ToIntFunction<ContentBlock> mediaEstimator) {
        java.util.Objects.requireNonNull(mediaEstimator, "mediaEstimator");
        int total = 0;
        if (messages != null) {
            for (Msg message : messages) {
                if (message == null) continue;
                int tokens = add(5, calculateTextToken(message.getName()));
                if (message.getRole() != null)
                    tokens = add(tokens, calculateTextToken(message.getRole().name()));
                if (message.getContent() != null) {
                    for (ContentBlock block : message.getContent())
                        tokens = add(tokens, blockTokens(block, mediaEstimator));
                }
                total = add(total, tokens);
            }
        }
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (tool == null) continue;
                total = add(total, 12);
                total = add(total, calculateTextToken(tool.getName()));
                total = add(total, calculateTextToken(tool.getDescription()));
                total = add(total, jsonTokens(tool.getParameters()));
                total = add(total, jsonTokens(tool.getOutputSchema()));
            }
        }
        return total;
    }

    private static int blockTokens(ContentBlock block, ToIntFunction<ContentBlock> mediaEstimator) {
        if (block == null) return 0;
        if (block instanceof TextBlock text) return calculateTextToken(text.getText());
        if (block instanceof ThinkingBlock thinking)
            return calculateTextToken(thinking.getThinking());
        if (block instanceof ToolUseBlock use) {
            int tokens =
                    add(
                            10,
                            add(
                                    calculateTextToken(use.getName()),
                                    calculateTextToken(use.getId())));
            // Content and input are two representations of the same arguments, not two inputs.
            return add(
                    tokens,
                    Math.max(calculateTextToken(use.getContent()), jsonTokens(use.getInput())));
        }
        if (block instanceof ToolResultBlock result) {
            int tokens =
                    add(
                            8,
                            add(
                                    calculateTextToken(result.getName()),
                                    calculateTextToken(result.getId())));
            if (result.getOutput() != null) {
                for (ContentBlock output : result.getOutput())
                    tokens = add(tokens, blockTokens(output, mediaEstimator));
            }
            return tokens;
        }
        int mediaTokens = mediaEstimator.applyAsInt(block);
        if (mediaTokens <= 0)
            throw new IllegalArgumentException("Media token estimate must be positive");
        return mediaTokens;
    }

    public static int calculateTextToken(String text) {
        return text == null || text.isEmpty() ? 0 : (int) Math.ceil(text.length() / 2.5);
    }

    private static int jsonTokens(Object value) {
        return value == null ? 0 : calculateTextToken(JsonUtils.getJsonCodec().toJson(value));
    }

    private static int add(int a, int b) {
        return cap((long) a + b);
    }

    private static int cap(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
