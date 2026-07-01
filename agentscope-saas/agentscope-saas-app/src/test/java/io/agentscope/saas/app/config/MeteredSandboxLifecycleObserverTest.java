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
package io.agentscope.saas.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.sandbox.SandboxAcquireResult.AcquisitionSource;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MeteredSandboxLifecycleObserverTest {

    @Test
    void recordsAcquireStartDurationWithSourceTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredSandboxLifecycleObserver observer =
                new MeteredSandboxLifecycleObserver("E2B", new SandboxMetrics(registry));

        observer.onAcquireStartSucceeded(
                null, AcquisitionSource.CREATE, TimeUnit.MILLISECONDS.toNanos(200));

        assertThat(
                        registry.get("saas.sandbox.acquire.duration")
                                .tag("type", "e2b")
                                .tag("source", "create")
                                .timer()
                                .count())
                .isEqualTo(1);
    }
}
