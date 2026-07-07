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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentTelemetryMiddlewareTest {

    @Test
    void recordsRunModelAndTokenMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentTelemetryMiddleware middleware =
                new AgentTelemetryMiddleware("qwen-test", new AgentRunMetrics(registry));

        middleware
                .onAgent(
                        null,
                        RuntimeContext.empty(),
                        new AgentInput(List.of()),
                        input ->
                                Flux.just(
                                        new ModelCallEndEvent("reply-1", new ChatUsage(3, 5, 0.1))))
                .collectList()
                .block();

        assertThat(
                        registry.find("saas.llm.model.calls")
                                .tag("model", "qwen-test")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.find("saas.llm.token.usage")
                                .tag("model", "qwen-test")
                                .tag("type", "total")
                                .counter()
                                .count())
                .isEqualTo(8.0);
        assertThat(
                        registry.find("saas.agent.run.duration")
                                .tag("model", "qwen-test")
                                .timer()
                                .count())
                .isEqualTo(1);
    }
}
