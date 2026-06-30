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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void refreshesInventoryBeforeAndAfterEviction() {
        SandboxEvictionJob job = new SandboxEvictionJob(broker, inventoryMetrics);
        when(broker.evictExpired()).thenReturn(2);

        job.evictExpired();

        InOrder inOrder = inOrder(inventoryMetrics, broker);
        inOrder.verify(inventoryMetrics).refresh();
        inOrder.verify(broker).evictExpired();
        inOrder.verify(inventoryMetrics).refresh();
    }

    @Test
    void metricsRefreshFailureDoesNotBlockEviction() {
        SandboxEvictionJob job = new SandboxEvictionJob(broker, inventoryMetrics);
        doThrow(new IllegalStateException("metrics down")).when(inventoryMetrics).refresh();

        job.evictExpired();

        verify(broker).evictExpired();
    }
}
