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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxExecutionTimeoutException;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MeteredSandboxExecutionGuardTest {

    @Test
    void recordsQueueAndActiveGaugesAroundSuccessfulLease() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredSandboxExecutionGuard guard =
                new MeteredSandboxExecutionGuard(
                        key -> SandboxLease.noop(), "E2B", new SandboxMetrics(registry));

        SandboxLease lease = guard.tryEnter(key());

        assertThat(
                        registry.get("saas.sandbox.request.queue_depth")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .gauge()
                                .value())
                .isEqualTo(0.0d);
        assertThat(
                        registry.get("saas.sandbox.execution.active")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .gauge()
                                .value())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.queue.wait.duration")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .tag("outcome", "acquired")
                                .timer()
                                .count())
                .isEqualTo(1);

        lease.close();
        lease.close();

        assertThat(
                        registry.get("saas.sandbox.execution.active")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .gauge()
                                .value())
                .isEqualTo(0.0d);
    }

    @Test
    void recordsTimeoutAndClearsQueueDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxExecutionGuard delegate =
                key -> {
                    throw new SandboxExecutionTimeoutException(key, Duration.ofMillis(5));
                };
        MeteredSandboxExecutionGuard guard =
                new MeteredSandboxExecutionGuard(delegate, "e2b", new SandboxMetrics(registry));

        assertThatThrownBy(() -> guard.tryEnter(key()))
                .isInstanceOf(SandboxExecutionTimeoutException.class);

        assertThat(
                        registry.get("saas.sandbox.request.queue_depth")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .gauge()
                                .value())
                .isEqualTo(0.0d);
        assertThat(
                        registry.get("saas.sandbox.queue.timeouts")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.queue.wait.duration")
                                .tag("type", "e2b")
                                .tag("scope", "user")
                                .tag("outcome", "timeout")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    private static SandboxIsolationKey key() {
        return SandboxIsolationKey.resolve(
                        IsolationScope.USER, RuntimeContext.builder().userId("u1").build(), "agent")
                .orElseThrow();
    }
}
