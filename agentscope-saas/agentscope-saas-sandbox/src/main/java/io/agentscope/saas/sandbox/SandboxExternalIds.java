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
package io.agentscope.saas.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Extracts low-cardinality backend identifiers from provider-specific sandbox state. */
public final class SandboxExternalIds {

    private static final List<String> PROVIDER_ID_GETTERS =
            List.of("getSandboxId", "getContainerId", "getContainerName", "getPodName");

    private SandboxExternalIds() {}

    public static Optional<String> fromRuntimeContext(RuntimeContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        return fromSandbox(ctx.get(Sandbox.class));
    }

    public static Optional<String> fromSandbox(Sandbox sandbox) {
        if (sandbox == null) {
            return Optional.empty();
        }
        return fromState(sandbox.getState());
    }

    public static Optional<String> fromState(SandboxState state) {
        if (state == null) {
            return Optional.empty();
        }
        for (String getter : PROVIDER_ID_GETTERS) {
            try {
                Method method = state.getClass().getMethod(getter);
                method.setAccessible(true);
                Object value = method.invoke(state);
                // A recognized provider with a null backend ID has not allocated a resource.
                // Its logical session ID must not be recorded as a physical backend ID.
                return value instanceof String s ? normalize(s) : Optional.empty();
            } catch (NoSuchMethodException absent) {
                // A provider-independent state may use its session ID below.
            } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                return Optional.empty();
            }
        }
        return normalize(state.getSessionId());
    }

    private static Optional<String> normalize(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
