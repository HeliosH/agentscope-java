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
package io.agentscope.extensions.redis.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionTimeoutException;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

class RedisSandboxExecutionGuardTest {

    @Test
    void timesOutWhenSlotStaysOccupiedPastMaxWait() {
        UnifiedJedis jedis = org.mockito.Mockito.mock(UnifiedJedis.class);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);
        RedisSandboxExecutionGuard guard =
                RedisSandboxExecutionGuard.builder(jedis)
                        .keyPrefix("test:")
                        .retryInterval(Duration.ofMillis(1))
                        .maxWait(Duration.ofMillis(5))
                        .build();

        SandboxExecutionTimeoutException error =
                assertThrows(SandboxExecutionTimeoutException.class, () -> guard.tryEnter(key()));

        assertNotNull(error.getIsolationKey());
    }

    @Test
    void retriesUntilSlotIsAcquiredAndReleasesWithCasScript() throws Exception {
        UnifiedJedis jedis = org.mockito.Mockito.mock(UnifiedJedis.class);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null, "OK");
        when(jedis.eval(anyString(), any(List.class), any(List.class))).thenReturn(1L);
        RedisSandboxExecutionGuard guard =
                RedisSandboxExecutionGuard.builder(jedis)
                        .keyPrefix("test:")
                        .retryInterval(Duration.ofMillis(1))
                        .maxWait(Duration.ofMillis(100))
                        .build();

        SandboxLease lease = guard.tryEnter(key());
        lease.close();

        verify(jedis).eval(anyString(), any(List.class), any(List.class));
    }

    private static SandboxIsolationKey key() {
        return SandboxIsolationKey.resolve(
                        IsolationScope.USER, RuntimeContext.builder().userId("u1").build(), "agent")
                .orElseThrow();
    }
}
