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
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.ratelimit.RateLimiter;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Second link in the SaaS middleware chain. Enforces a per-org request rate limit (sliding window)
 * before the agent runs. On limit exceeded it raises {@link QuotaExceededException}, which the chat
 * endpoint maps to HTTP 429.
 */
public class RateLimitMiddleware implements MiddlewareBase {

    private final RateLimiter rateLimiter;
    private final int maxRequests;
    private final int windowSeconds;

    public RateLimitMiddleware(RateLimiter rateLimiter, int maxRequests, int windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = ctx.get(TenantContext.class);
        String bucket = tc != null && tc.orgId() != null ? tc.orgId() : "anonymous";
        if (!rateLimiter.tryAcquire(bucket, maxRequests, windowSeconds)) {
            return Flux.error(
                    new QuotaExceededException(
                            "Rate limit exceeded for org "
                                    + bucket
                                    + " ("
                                    + maxRequests
                                    + "/"
                                    + windowSeconds
                                    + "s)"));
        }
        return next.apply(input);
    }
}
