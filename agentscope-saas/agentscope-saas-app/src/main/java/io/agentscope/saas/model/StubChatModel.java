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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * A zero-dependency echo model used by the {@code local} profile to validate the full request →
 * middleware → agent → AG-UI SSE path without any external LLM. It streams a short canned reply
 * word-by-word (as text deltas) so the streaming UI can be exercised offline.
 */
public class StubChatModel implements Model {

    private static final String MODEL_NAME = "stub-echo";

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String lastUser =
                messages == null || messages.isEmpty()
                        ? ""
                        : messages.get(messages.size() - 1).getTextContent();
        String reply =
                "Hello from the AgentScope SaaS stub model. You said: \""
                        + (lastUser == null ? "" : lastUser.trim())
                        + "\".";
        String id = UUID.randomUUID().toString();
        String[] words = reply.split(" ");

        // Emit each word (with trailing space) as a separate text delta chunk.
        return Flux.fromArray(words)
                .index()
                .concatMap(
                        tuple -> {
                            long idx = tuple.getT1();
                            String word = tuple.getT2();
                            String delta = idx == 0 ? word : " " + word;
                            ContentBlock block = TextBlock.builder().text(delta).build();
                            ChatResponse chunk =
                                    ChatResponse.builder()
                                            .id(id)
                                            .content(List.of(block))
                                            .finishReason(idx == words.length - 1 ? "stop" : null)
                                            .build();
                            return Flux.just(chunk).delayElements(Duration.ofMillis(40));
                        });
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
}
