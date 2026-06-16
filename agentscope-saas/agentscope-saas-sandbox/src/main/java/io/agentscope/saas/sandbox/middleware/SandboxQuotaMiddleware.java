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
package io.agentscope.saas.sandbox.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.sandbox.SandboxBroker;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Pre-acquire quota check middleware. Runs before the framework's
 * {@code SandboxLifecycleMiddleware} to enforce per-user concurrent sandbox limits
 * ({@code TenantContext.maxSandboxes}). When the user's active sandbox count is at or above
 * their tier limit, this middleware raises {@link QuotaExceededException} (→ HTTP 429).
 *
 * <p>The actual sandbox lifecycle (create/resume/stop/persist) is managed entirely by the
 * framework. This middleware only gates access based on the tracked quota.
 */
public class SandboxQuotaMiddleware implements MiddlewareBase {

    private final SandboxBroker broker;

    public SandboxQuotaMiddleware(SandboxBroker broker) {
        this.broker = broker;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        TenantContext tc = ctx.get(TenantContext.class);
        if (tc != null && tc.orgId() != null && tc.userId() != null) {
            broker.checkQuota(
                    UUID.fromString(tc.orgId()), UUID.fromString(tc.userId()), tc.maxSandboxes());
        }
        return next.apply(input);
    }
}
