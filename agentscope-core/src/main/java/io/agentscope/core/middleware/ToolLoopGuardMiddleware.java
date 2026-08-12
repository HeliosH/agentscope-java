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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Stops an agent run when it repeatedly emits the same tool call without making progress. */
public final class ToolLoopGuardMiddleware implements MiddlewareBase {

    private final int repeatThreshold;
    private final int windowSize;

    public ToolLoopGuardMiddleware(int repeatThreshold, int windowSize) {
        if (repeatThreshold < 2) {
            throw new IllegalArgumentException("repeatThreshold must be at least 2");
        }
        if (windowSize < repeatThreshold) {
            throw new IllegalArgumentException("windowSize must be at least repeatThreshold");
        }
        this.repeatThreshold = repeatThreshold;
        this.windowSize = windowSize;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        ctx.put(LoopState.class, new LoopState(windowSize));
        return next.apply(input).doFinally(ignored -> ctx.put(LoopState.class, null));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    LoopState state = ctx.get(LoopState.class);
                    if (state == null) {
                        state = new LoopState(windowSize);
                        ctx.put(LoopState.class, state);
                    }
                    for (ToolUseBlock call : input.toolCalls()) {
                        String fingerprint = fingerprint(call);
                        int occurrences = state.record(fingerprint);
                        if (occurrences >= repeatThreshold) {
                            return Flux.error(
                                    new ToolLoopDetectedException(
                                            call.getName(), fingerprint, occurrences, windowSize));
                        }
                    }
                    return next.apply(input);
                });
    }

    static String fingerprint(ToolUseBlock call) {
        String canonical = call.getName() + ":" + canonical(call.getInput());
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                out.append(String.valueOf(entry.getKey()))
                        .append('=')
                        .append(canonical(entry.getValue()))
                        .append(';');
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : iterable) {
                out.append(canonical(item)).append(';');
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < Array.getLength(value); i++) {
                out.append(canonical(Array.get(value, i))).append(';');
            }
            return out.append(']').toString();
        }
        return value.getClass().getName() + ':' + Objects.toString(value);
    }

    private static final class LoopState {
        private final int windowSize;
        private final Deque<String> recent = new ArrayDeque<>();

        private LoopState(int windowSize) {
            this.windowSize = windowSize;
        }

        private synchronized int record(String fingerprint) {
            recent.addLast(fingerprint);
            while (recent.size() > windowSize) {
                recent.removeFirst();
            }
            int occurrences = 0;
            for (String item : recent) {
                if (fingerprint.equals(item)) {
                    occurrences++;
                }
            }
            return occurrences;
        }
    }

    /** Contains only a tool name and an input hash so sensitive arguments never enter logs. */
    public static final class ToolLoopDetectedException extends RuntimeException {
        private final String toolName;
        private final String fingerprint;

        private ToolLoopDetectedException(
                String toolName, String fingerprint, int occurrences, int windowSize) {
            super(
                    "Repeated tool-call loop detected: tool="
                            + toolName
                            + ", fingerprint="
                            + fingerprint.substring(0, 12)
                            + ", occurrences="
                            + occurrences
                            + ", window="
                            + windowSize);
            this.toolName = toolName;
            this.fingerprint = fingerprint;
        }

        public String getToolName() {
            return toolName;
        }

        public String getFingerprint() {
            return fingerprint;
        }
    }
}
