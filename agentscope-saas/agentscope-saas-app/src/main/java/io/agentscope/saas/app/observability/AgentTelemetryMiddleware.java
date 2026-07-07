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
package io.agentscope.saas.app.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatUsage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/** Emits run duration, model call, and token metrics without tagging tenant/user identifiers. */
public final class AgentTelemetryMiddleware implements MiddlewareBase {

    private final String modelName;
    private final AgentRunMetrics metrics;

    public AgentTelemetryMiddleware(String modelName, AgentRunMetrics metrics) {
        this.modelName = modelName;
        this.metrics = metrics != null ? metrics : AgentRunMetrics.noop();
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        long startNanos = System.nanoTime();
        AtomicReference<String> outcome = new AtomicReference<>("success");
        return next.apply(input)
                .doOnNext(this::recordEvent)
                .doOnError(error -> outcome.set("error"))
                .doFinally(
                        signal -> {
                            if (signal == SignalType.CANCEL) {
                                outcome.set("cancel");
                            }
                            metrics.recordAgentRun(
                                    modelName,
                                    outcome.get(),
                                    signal.name(),
                                    System.nanoTime() - startNanos);
                        });
    }

    private void recordEvent(AgentEvent event) {
        if (!(event instanceof ModelCallEndEvent modelCallEnd)) {
            return;
        }
        metrics.recordModelCall(modelName, "success");
        ChatUsage usage = modelCallEnd.getUsage();
        if (usage == null) {
            return;
        }
        metrics.recordTokenUsage(modelName, "input", usage.getInputTokens());
        metrics.recordTokenUsage(modelName, "output", usage.getOutputTokens());
        metrics.recordTokenUsage(modelName, "total", usage.getTotalTokens());
    }
}
