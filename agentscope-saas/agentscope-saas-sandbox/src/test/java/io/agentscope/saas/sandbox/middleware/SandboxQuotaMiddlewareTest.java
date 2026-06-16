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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.sandbox.SandboxBroker;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/** Unit tests for {@link SandboxQuotaMiddleware}. */
@ExtendWith(MockitoExtension.class)
class SandboxQuotaMiddlewareTest {

    @Mock private SandboxBroker broker;
    @Mock private Agent agent;
    @Mock private Function<AgentInput, Flux<AgentEvent>> next;

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private SandboxQuotaMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new SandboxQuotaMiddleware(broker);
    }

    @Test
    void passesThroughWhenQuotaAvailable() {
        TenantContext tc =
                new TenantContext(
                        ORG_ID.toString(), USER_ID.toString(), "member", "standard", 3, 10000L);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId(USER_ID.toString())
                        .put(TenantContext.class, tc)
                        .build();

        middleware.onAgent(agent, ctx, new AgentInput(Collections.emptyList()), next::apply);

        verify(broker).checkQuota(ORG_ID, USER_ID, 3);
    }

    @Test
    void rejectsWhenQuotaExceeded() {
        TenantContext tc =
                new TenantContext(
                        ORG_ID.toString(), USER_ID.toString(), "member", "standard", 1, 10000L);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId(USER_ID.toString())
                        .put(TenantContext.class, tc)
                        .build();

        doThrow(new QuotaExceededException("Sandbox quota exceeded"))
                .when(broker)
                .checkQuota(ORG_ID, USER_ID, 1);

        // checkQuota throws synchronously before the Flux is created
        assertThrows(
                QuotaExceededException.class,
                () ->
                        middleware.onAgent(
                                agent, ctx, new AgentInput(Collections.emptyList()), next::apply));
    }

    @Test
    void skipsCheckWhenNoTenantContext() {
        RuntimeContext ctx = RuntimeContext.builder().build();

        middleware.onAgent(agent, ctx, new AgentInput(Collections.emptyList()), next::apply);

        verifyNoInteractions(broker);
    }
}
