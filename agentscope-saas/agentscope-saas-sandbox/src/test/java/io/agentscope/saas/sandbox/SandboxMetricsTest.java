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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SandboxMetrics}. */
class SandboxMetricsTest {

    @Test
    void recordsLifecycleCountersWithLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxMetrics metrics = new SandboxMetrics(registry);

        metrics.registerActive("E2B");
        metrics.release("e2b");
        metrics.quotaRejected("e2b");
        metrics.workspaceProjectionFailed("e2b");

        assertThat(
                        registry.get("saas.sandbox.lifecycle.events")
                                .tag("type", "e2b")
                                .tag("event", "registered")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.lifecycle.events")
                                .tag("type", "e2b")
                                .tag("event", "released")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.lifecycle.events")
                                .tag("type", "e2b")
                                .tag("event", "quota_rejected")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.lifecycle.events")
                                .tag("type", "e2b")
                                .tag("event", "workspace_projection_failed")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
    }

    @Test
    void recordsRunDurationByBackendAndTerminalSignal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxMetrics metrics = new SandboxMetrics(registry);

        metrics.recordRun("e2b", "on_complete", TimeUnit.MILLISECONDS.toNanos(42));

        assertThat(
                        registry.get("saas.sandbox.run.duration")
                                .tag("type", "e2b")
                                .tag("signal", "on_complete")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void noopMetricsAcceptEvents() {
        SandboxMetrics metrics = SandboxMetrics.noop();

        metrics.registerActive("e2b");
        metrics.workspaceProjectionSucceeded("e2b");
        metrics.backendReleaseFailed("e2b");
        metrics.recordRun("e2b", "on_complete", 1);
    }
}
