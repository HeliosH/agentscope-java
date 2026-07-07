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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Low-cardinality business/runtime metrics for enterprise assistant runs. */
@Component
public class AgentRunMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    @Autowired
    public AgentRunMetrics(MeterRegistry registry) {
        this(registry, true);
    }

    private AgentRunMetrics(MeterRegistry registry, boolean registerBaseMeters) {
        this.registry = registry;
        if (registerBaseMeters) {
            registerBaseMeters();
        }
    }

    public static AgentRunMetrics noop() {
        return new AgentRunMetrics(null, false);
    }

    public void recordChatStream(
            String outcome, boolean persisted, String sandboxType, long durationNanos) {
        if (registry == null || durationNanos < 0) {
            return;
        }
        Timer.builder("saas.agent.chat.stream.duration")
                .description("End-to-end chat stream duration observed at the SaaS API boundary")
                .tag("outcome", normalize(outcome))
                .tag("persisted", persisted ? "true" : "false")
                .tag("sandbox_type", normalize(sandboxType))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordAgentRun(
            String model, String outcome, String signalType, long durationNanos) {
        if (registry == null || durationNanos < 0) {
            return;
        }
        Timer.builder("saas.agent.run.duration")
                .description("Agent runtime duration from middleware entry to terminal signal")
                .tag("model", normalize(model))
                .tag("outcome", normalize(outcome))
                .tag("signal", normalize(signalType))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordModelCall(String model, String outcome) {
        if (registry == null) {
            return;
        }
        counter(
                        "saas.llm.model.calls",
                        "LLM model call count observed from model call end events",
                        "model",
                        normalize(model),
                        "outcome",
                        normalize(outcome))
                .increment();
    }

    public void recordTokenUsage(String model, String tokenType, long tokens) {
        if (registry == null || tokens <= 0) {
            return;
        }
        counter(
                        "saas.llm.token.usage",
                        "LLM token usage observed from ChatUsage",
                        "model",
                        normalize(model),
                        "type",
                        normalize(tokenType))
                .increment(tokens);
    }

    public void recordDegradation(String component, String status, String action) {
        if (registry == null) {
            return;
        }
        counter(
                        "saas.degradation.events",
                        "Dependency degradation observations and policy decisions",
                        "component",
                        normalize(component),
                        "status",
                        normalize(status),
                        "action",
                        normalize(action))
                .increment();
    }

    private void registerBaseMeters() {
        if (registry == null) {
            return;
        }
        Timer.builder("saas.agent.chat.stream.duration")
                .description("End-to-end chat stream duration observed at the SaaS API boundary")
                .tag("outcome", "none")
                .tag("persisted", "false")
                .tag("sandbox_type", UNKNOWN)
                .register(registry);
        Timer.builder("saas.agent.run.duration")
                .description("Agent runtime duration from middleware entry to terminal signal")
                .tag("model", UNKNOWN)
                .tag("outcome", "none")
                .tag("signal", "none")
                .register(registry);
        counter(
                "saas.llm.model.calls",
                "LLM model call count observed from model call end events",
                "model",
                UNKNOWN,
                "outcome",
                "none");
        counter(
                "saas.llm.token.usage",
                "LLM token usage observed from ChatUsage",
                "model",
                UNKNOWN,
                "type",
                "total");
        counter(
                "saas.degradation.events",
                "Dependency degradation observations and policy decisions",
                "component",
                "bootstrap",
                "status",
                UNKNOWN,
                "action",
                "observe");
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
