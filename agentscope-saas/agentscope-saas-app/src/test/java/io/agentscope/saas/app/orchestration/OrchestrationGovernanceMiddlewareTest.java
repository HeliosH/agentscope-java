/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OrchestrationGovernanceMiddlewareTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_RUN_ID = UUID.randomUUID();

    @Test
    void cancelsAStalledExecutionAtThePersistedDeadline() {
        OrchestrationGovernanceService governance = mock(OrchestrationGovernanceService.class);
        when(governance.preflight(ORG_ID, RUN_ID, AGENT_RUN_ID))
                .thenReturn(
                        new OrchestrationGovernanceService.BudgetDecision(true, null, null),
                        new OrchestrationGovernanceService.BudgetDecision(
                                false,
                                "RUN_DEADLINE_EXCEEDED",
                                "Execution stopped by orchestration governance"));
        when(governance.remainingTime(ORG_ID, RUN_ID, AGENT_RUN_ID))
                .thenReturn(Optional.of(Duration.ofMillis(20)));
        var middleware = new OrchestrationGovernanceMiddleware(governance, new ObjectMapper());

        StepVerifier.create(
                        middleware.onAgent(
                                mock(Agent.class), context(), input(), ignored -> Flux.never()))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error)
                                    .isInstanceOf(
                                            OrchestrationGovernanceMiddleware
                                                    .BudgetExceededException.class);
                            assertThat(
                                            ((OrchestrationGovernanceMiddleware
                                                                    .BudgetExceededException)
                                                            error)
                                                    .getReason())
                                    .isEqualTo("RUN_DEADLINE_EXCEEDED");
                        })
                .verify(Duration.ofSeconds(2));

        verify(governance, times(2)).preflight(ORG_ID, RUN_ID, AGENT_RUN_ID);
    }

    @Test
    void capturesOneImmutableRuntimeCapabilitySnapshotBeforeModelExecution() {
        OrchestrationGovernanceService governance = mock(OrchestrationGovernanceService.class);
        var middleware = new OrchestrationGovernanceMiddleware(governance, new ObjectMapper());
        RuntimeContext context = context();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("enterprise-model");
        ModelCallInput modelInput =
                new ModelCallInput(
                        input().msgs(),
                        List.of(
                                ToolSchema.builder()
                                        .name("lookup")
                                        .description("lookup")
                                        .parameters(Map.of("type", "object"))
                                        .build()),
                        null,
                        model);

        middleware
                .onModelCall(mock(Agent.class), context, modelInput, ignored -> Flux.empty())
                .then()
                .block();
        middleware
                .onModelCall(mock(Agent.class), context, modelInput, ignored -> Flux.empty())
                .then()
                .block();

        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(governance)
                .saveRuntimeCapabilitySnapshot(
                        org.mockito.ArgumentMatchers.eq(ORG_ID),
                        org.mockito.ArgumentMatchers.eq(RUN_ID),
                        org.mockito.ArgumentMatchers.eq(AGENT_RUN_ID),
                        json.capture(),
                        hash.capture());
        assertThat(json.getValue()).contains("enterprise-model", "lookup", "toolSchemaHash");
        assertThat(hash.getValue()).hasSize(64);
    }

    private static RuntimeContext context() {
        TenantContext tenant =
                new TenantContext(
                        ORG_ID.toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        2,
                        100_000);
        return RuntimeContext.builder()
                .userId(tenant.userId())
                .sessionId(UUID.randomUUID().toString())
                .put(TenantContext.ATTR_KEY, tenant)
                .put(RunOrchestrationService.ATTR_RUN_ID, RUN_ID.toString())
                .put(RunOrchestrationService.ATTR_AGENT_RUN_ID, AGENT_RUN_ID.toString())
                .build();
    }

    private static AgentInput input() {
        return new AgentInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent("stalled").build()));
    }
}
