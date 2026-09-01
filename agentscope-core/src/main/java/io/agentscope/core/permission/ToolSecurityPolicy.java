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
package io.agentscope.core.permission;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Optional external policy hook evaluated before a concrete tool invocation.
 *
 * <p>The hook is deliberately expressed in terms of the core permission model rather than a
 * vendor protocol. Applications can connect an internal security gateway, a local policy engine,
 * or a test double without coupling the runtime to that implementation. The returned decision is
 * merged with the built-in rules using the most restrictive result.
 */
@FunctionalInterface
public interface ToolSecurityPolicy {

    /** A no-op policy used when external security is disabled. */
    ToolSecurityPolicy ALLOW_ALL = request -> Mono.just(PermissionDecision.passthrough("disabled"));

    /**
     * Evaluates a tool invocation.
     *
     * @param request immutable invocation context
     * @return a decision; {@code null} or an empty Mono is treated as PASSTHROUGH
     */
    Mono<PermissionDecision> evaluate(Request request);

    /** Input supplied to an external policy implementation. */
    record Request(
            String agentName,
            RuntimeContext runtimeContext,
            ToolBase tool,
            ToolUseBlock toolUse,
            Map<String, Object> input) {

        public Request {
            input =
                    input == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }
}
