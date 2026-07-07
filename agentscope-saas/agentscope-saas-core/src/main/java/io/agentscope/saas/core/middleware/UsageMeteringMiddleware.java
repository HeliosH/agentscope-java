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
import io.agentscope.core.model.ChatUsage;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.usage.UsageService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Third link in the SaaS middleware chain. Meters resource usage per agent run (metering only — no
 * billing). It counts model calls during the run and, on completion, records a usage metric for the
 * tenant. It records both model-call counts and token usage when the model provider returns
 * {@link ChatUsage}; writes are best-effort through {@link UsageService}.
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
        TenantContext tc = TenantContext.from(ctx);
        AtomicLong modelCalls = new AtomicLong(0);
        AtomicLong inputTokens = new AtomicLong(0);
        AtomicLong outputTokens = new AtomicLong(0);
        AtomicLong totalTokens = new AtomicLong(0);
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ModelCallEndEvent modelCallEnd) {
                                modelCalls.incrementAndGet();
                                ChatUsage usage = modelCallEnd.getUsage();
                                if (usage != null) {
                                    inputTokens.addAndGet(Math.max(0, usage.getInputTokens()));
                                    outputTokens.addAndGet(Math.max(0, usage.getOutputTokens()));
                                    totalTokens.addAndGet(Math.max(0, usage.getTotalTokens()));
                                }
                            }
                        })
                .doFinally(
                        signal -> {
                            if (tc == null) {
                                return;
                            }
                            recordPositive(tc, "model_calls", modelCalls.get());
                            recordPositive(tc, "tokens_input", inputTokens.get());
                            recordPositive(tc, "tokens_output", outputTokens.get());
                            recordPositive(tc, "tokens_total", totalTokens.get());
                        });
    }

    private void recordPositive(TenantContext tenant, String metric, long value) {
        if (value > 0) {
            usageService.record(tenant, metric, value, null);
        }
    }
}
