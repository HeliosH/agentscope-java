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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SandboxEvictionJob}. */
@ExtendWith(MockitoExtension.class)
class SandboxEvictionJobTest {

    @Mock private SandboxBroker broker;
    @Mock private SandboxInventoryMetrics inventoryMetrics;
    @Mock private SandboxBackendTerminator terminator;
    @Mock private SandboxMetrics metrics;

    @Test
    void refreshesInventoryBeforeAndAfterEviction() {
        SandboxEvictionJob job =
                new SandboxEvictionJob(broker, inventoryMetrics, terminator, metrics);
        SandboxBroker.EvictedSandbox evicted1 =
                new SandboxBroker.EvictedSandbox(
                        UUID.randomUUID(),
                        "opensandbox",
                        "sandbox-1",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        SandboxBroker.EvictedSandbox evicted2 =
                new SandboxBroker.EvictedSandbox(
                        UUID.randomUUID(),
                        "e2b",
                        "sandbox-2",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        when(broker.evictExpiredWithDetails()).thenReturn(List.of(evicted1, evicted2));
        when(terminator.terminate("opensandbox", "sandbox-1"))
                .thenReturn(SandboxBackendTerminator.TerminationResult.success());
        when(terminator.terminate("e2b", "sandbox-2"))
                .thenReturn(SandboxBackendTerminator.TerminationResult.success());

        job.evictExpired();

        InOrder inOrder = inOrder(inventoryMetrics, broker);
        inOrder.verify(inventoryMetrics).refresh();
        inOrder.verify(broker).evictExpiredWithDetails();
        inOrder.verify(inventoryMetrics).refresh();
        verify(terminator).terminate("opensandbox", "sandbox-1");
        verify(terminator).terminate("e2b", "sandbox-2");
        verify(metrics).backendReleaseSucceeded("opensandbox");
        verify(metrics).backendReleaseSucceeded("e2b");
    }

    @Test
    void metricsRefreshFailureDoesNotBlockEviction() {
        SandboxEvictionJob job =
                new SandboxEvictionJob(broker, inventoryMetrics, terminator, metrics);
        doThrow(new IllegalStateException("metrics down")).when(inventoryMetrics).refresh();

        job.evictExpired();

        verify(broker).evictExpiredWithDetails();
    }

    @Test
    void backendTerminationFailureDoesNotBlockRemainingEvictions() {
        SandboxEvictionJob job =
                new SandboxEvictionJob(broker, inventoryMetrics, terminator, metrics);
        SandboxBroker.EvictedSandbox failed =
                new SandboxBroker.EvictedSandbox(
                        UUID.randomUUID(), "e2b", "failed-1", UUID.randomUUID(), UUID.randomUUID());
        SandboxBroker.EvictedSandbox skipped =
                new SandboxBroker.EvictedSandbox(
                        UUID.randomUUID(), "docker", null, UUID.randomUUID(), UUID.randomUUID());
        when(broker.evictExpiredWithDetails()).thenReturn(List.of(failed, skipped));
        when(terminator.terminate("e2b", "failed-1"))
                .thenReturn(SandboxBackendTerminator.TerminationResult.failed("provider down"));
        when(terminator.terminate("docker", null))
                .thenReturn(SandboxBackendTerminator.TerminationResult.noExternalId());

        job.evictExpired();

        verify(metrics).backendReleaseFailed("e2b");
        verify(metrics, never()).backendReleaseFailed("docker");
    }
}
