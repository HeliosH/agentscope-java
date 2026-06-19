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
package io.agentscope.saas.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * First link in the SaaS middleware chain. The tenant context is injected into the
 * {@link RuntimeContext} by the chat controller (which has access to the request principal) before
 * the agent runs; this middleware validates its presence and tags the reactive context for logging
 * and downstream middlewares. It is intentionally lightweight — the heavy lifting (sandbox
 * lifecycle, permission, trace) is performed by framework-provided middlewares that run after this.
 */
public class TenantContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TenantContextMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = TenantContext.from(ctx);
        if (tc == null) {
            log.warn(
                    "No TenantContext present on RuntimeContext for agent {}; proceeding as"
                            + " anonymous",
                    agent.getAgentId());
        } else if (log.isDebugEnabled()) {
            log.debug(
                    "Agent {} invoked for org={} user={} tier={}",
                    agent.getAgentId(),
                    tc.orgId(),
                    tc.userId(),
                    tc.tier());
        }
        return next.apply(input);
    }
}
