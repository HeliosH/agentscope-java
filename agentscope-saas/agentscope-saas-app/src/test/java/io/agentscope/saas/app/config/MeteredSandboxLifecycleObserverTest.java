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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult.AcquisitionSource;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.agentscope.saas.sandbox.SandboxTrackingContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
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

    @Test
    void updatesTrackingRowWithProviderSandboxIdAfterAcquireSuccess() {
        UUID trackingId = UUID.randomUUID();
        String orgId = "00000000-0000-0000-0000-000000000001";
        SandboxBroker broker = mock(SandboxBroker.class);
        doAnswer(
                        invocation -> {
                            assertThat(TenantContextHolder.getOrgId()).isEqualTo(orgId);
                            return null;
                        })
                .when(broker)
                .updateExternalId(trackingId, "provider-sandbox-1");
        ProviderState state = new ProviderState();
        state.setSessionId("sess-1");
        state.setSandboxId("provider-sandbox-1");
        Sandbox sandbox = mock(Sandbox.class);
        org.mockito.Mockito.when(sandbox.getState()).thenReturn(state);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(
                                SandboxTrackingContext.class,
                                new SandboxTrackingContext(trackingId, orgId))
                        .put(Sandbox.class, sandbox)
                        .build();

        MeteredSandboxLifecycleObserver observer =
                new MeteredSandboxLifecycleObserver(
                        "e2b", new SandboxMetrics(new SimpleMeterRegistry()), broker);

        observer.onAcquireStartSucceeded(ctx, AcquisitionSource.CREATE, 1L);

        verify(broker).updateExternalId(trackingId, "provider-sandbox-1");
        assertThat(TenantContextHolder.getOrgId()).isNull();
    }

    private static final class ProviderState extends SandboxState {
        private String sandboxId;

        public String getSandboxId() {
            return sandboxId;
        }

        void setSandboxId(String sandboxId) {
            this.sandboxId = sandboxId;
        }
    }
}
