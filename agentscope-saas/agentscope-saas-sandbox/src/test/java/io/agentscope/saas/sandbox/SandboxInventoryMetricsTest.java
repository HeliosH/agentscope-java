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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SandboxInventoryMetrics}. */
@ExtendWith(MockitoExtension.class)
class SandboxInventoryMetricsTest {

    @Mock private SandboxRepository sandboxRepository;

    @Test
    void refreshPublishesPoolAndExpiredActiveGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxInventoryMetrics metrics = new SandboxInventoryMetrics(registry, sandboxRepository);
        when(sandboxRepository.countBySandboxTypeAndStatus())
                .thenReturn(
                        List.of(
                                new PoolCount("E2B", "active", 2),
                                new PoolCount("cube", "evicted", 1)));
        when(sandboxRepository.countExpiredActiveBySandboxType(any(OffsetDateTime.class)))
                .thenReturn(List.of(new TypeCount("E2B", 1)));

        metrics.refresh();

        assertThat(
                        registry.get("saas.sandbox.pool.size")
                                .tag("type", "e2b")
                                .tag("status", "active")
                                .gauge()
                                .value())
                .isEqualTo(2.0d);
        assertThat(
                        registry.get("saas.sandbox.pool.size")
                                .tag("type", "cube")
                                .tag("status", "evicted")
                                .gauge()
                                .value())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get("saas.sandbox.pool.expired_active")
                                .tag("type", "e2b")
                                .gauge()
                                .value())
                .isEqualTo(1.0d);
    }

    private record PoolCount(String sandboxType, String status, long count)
            implements SandboxRepository.SandboxPoolCount {

        @Override
        public String getSandboxType() {
            return sandboxType;
        }

        @Override
        public String getStatus() {
            return status;
        }

        @Override
        public long getCount() {
            return count;
        }
    }

    private record TypeCount(String sandboxType, long count)
            implements SandboxRepository.SandboxTypeCount {

        @Override
        public String getSandboxType() {
            return sandboxType;
        }

        @Override
        public long getCount() {
            return count;
        }
    }
}
