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
package io.agentscope.saas.core.middleware;

import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.usage.UsageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class UsageMeteringMiddlewareTest {

    private final UsageService usageService = org.mockito.Mockito.mock(UsageService.class);
    private final UsageMeteringMiddleware middleware = new UsageMeteringMiddleware(usageService);

    @Test
    void recordsModelCallsAndTokens() {
        TenantContext tenant =
                new TenantContext(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        2,
                        1000);
        RuntimeContext ctx = RuntimeContext.builder().put(TenantContext.class, tenant).build();

        middleware
                .onAgent(
                        null,
                        ctx,
                        new AgentInput(List.of()),
                        input ->
                                Flux.just(
                                        new ModelCallEndEvent(
                                                "reply-1", new ChatUsage(11, 17, 0.25))))
                .collectList()
                .block();

        verify(usageService).record(tenant, "model_calls", 1L, null);
        verify(usageService).record(tenant, "tokens_input", 11L, null);
        verify(usageService).record(tenant, "tokens_output", 17L, null);
        verify(usageService).record(tenant, "tokens_total", 28L, null);
    }
}
