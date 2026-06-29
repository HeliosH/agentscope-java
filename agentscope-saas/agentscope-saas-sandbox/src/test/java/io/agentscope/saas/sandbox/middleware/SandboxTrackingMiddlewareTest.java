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
package io.agentscope.saas.sandbox.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/** Unit tests for {@link SandboxTrackingMiddleware}. */
@ExtendWith(MockitoExtension.class)
class SandboxTrackingMiddlewareTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock private SandboxBroker broker;
    @Mock private Agent agent;
    @Mock private Function<AgentInput, Flux<AgentEvent>> next;

    private SandboxTrackingMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new SandboxTrackingMiddleware(broker, "e2b", 60);
    }

    @Test
    void propagatesQuotaExceededFromTransactionalRegistration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        middleware = new SandboxTrackingMiddleware(broker, "e2b", 60, new SandboxMetrics(registry));
        RuntimeContext ctx = tenantContext();
        AgentInput input = new AgentInput(Collections.emptyList());
        doThrow(new QuotaExceededException("Sandbox quota exceeded"))
                .when(broker)
                .registerActive(
                        any(UUID.class),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(OffsetDateTime.class),
                        anyInt());

        assertThrows(
                QuotaExceededException.class, () -> middleware.onAgent(agent, ctx, input, next));

        verify(next, never()).apply(input);
        assertThat(
                        registry.get("saas.sandbox.lifecycle.events")
                                .tag("type", "e2b")
                                .tag("event", "quota_rejected")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
    }

    @Test
    void releasesTrackingRowWhenDownstreamThrowsSynchronously() {
        RuntimeContext ctx = tenantContext();
        AgentInput input = new AgentInput(Collections.emptyList());
        UUID sandboxId = UUID.randomUUID();
        when(broker.registerActive(
                        any(UUID.class),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(OffsetDateTime.class),
                        anyInt()))
                .thenReturn(sandboxId);
        when(next.apply(input)).thenThrow(new IllegalStateException("boom"));

        assertThrows(
                IllegalStateException.class, () -> middleware.onAgent(agent, ctx, input, next));

        verify(broker).release(sandboxId);
    }

    @Test
    void recordsRunDurationWhenDownstreamCompletes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        middleware = new SandboxTrackingMiddleware(broker, "e2b", 60, new SandboxMetrics(registry));
        RuntimeContext ctx = tenantContext();
        AgentInput input = new AgentInput(Collections.emptyList());
        UUID sandboxId = UUID.randomUUID();
        when(broker.registerActive(
                        any(UUID.class),
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(OffsetDateTime.class),
                        anyInt()))
                .thenReturn(sandboxId);
        when(next.apply(input)).thenReturn(Flux.empty());

        middleware.onAgent(agent, ctx, input, next).blockLast();

        verify(broker).release(sandboxId);
        assertThat(
                        registry.get("saas.sandbox.run.duration")
                                .tag("type", "e2b")
                                .tag("signal", "on_complete")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    private static RuntimeContext tenantContext() {
        TenantContext tc =
                new TenantContext(
                        ORG_ID.toString(), USER_ID.toString(), "member", "standard", 1, 10000L);
        return RuntimeContext.builder()
                .userId(USER_ID.toString())
                .sessionId("sess-1")
                .put(TenantContext.class, tc)
                .build();
    }
}
