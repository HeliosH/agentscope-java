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
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.usage.UsageService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Third link in the SaaS middleware chain. Meters resource usage per agent run (metering only — no
 * billing). It counts model calls during the run and, on completion, records a usage metric for the
 * tenant. Token-accurate accounting is wired in a later phase via {@code ChatUsage}; Phase 1 records
 * a model-invocation count which is sufficient to validate the metering path end-to-end.
 */
public class UsageMeteringMiddleware implements MiddlewareBase {

    private final UsageService usageService;

    public UsageMeteringMiddleware(UsageService usageService) {
        this.usageService = usageService;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = ctx.get(TenantContext.class);
        AtomicLong modelCalls = new AtomicLong(0);
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ModelCallEndEvent) {
                                modelCalls.incrementAndGet();
                            }
                        })
                .doFinally(
                        signal -> {
                            long calls = modelCalls.get();
                            if (calls > 0 && tc != null) {
                                usageService.record(tc, "model_calls", calls, null);
                            }
                        });
    }
}
