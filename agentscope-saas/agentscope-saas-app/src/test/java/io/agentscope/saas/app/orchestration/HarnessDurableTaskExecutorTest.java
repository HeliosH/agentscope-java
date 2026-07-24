/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.config.TenantRlsWebFilter;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class HarnessDurableTaskExecutorTest {

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void reconstructsTenantContextAndMaterializesRequestedSubagent() throws Exception {
        HarnessAgent parent = mock(HarnessAgent.class);
        ReActAgent child = mock(ReActAgent.class);
        UUID orgId = UUID.randomUUID();
        when(parent.createSubagentIfPresent(eq("researcher"), any(RuntimeContext.class)))
                .thenReturn(Optional.of(child));
        when(child.call(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        ignored -> {
                            assertThat(TenantContextHolder.getOrgId()).isEqualTo(orgId.toString());
                            return Mono.deferContextual(
                                    reactorContext -> {
                                        String propagatedOrgId =
                                                reactorContext.get(TenantRlsWebFilter.ORG_ID_KEY);
                                        assertThat(propagatedOrgId).isEqualTo(orgId.toString());
                                        return Mono.just(
                                                Msg.builder()
                                                        .role(MsgRole.ASSISTANT)
                                                        .textContent("durable research result")
                                                        .build());
                                    });
                        });
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent, new ObjectMapper(), properties, chatPersistence, orchestration);
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        orgId,
                        runId,
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        agentRunId,
                        "researcher",
                        "sub-session-1",
                        "member",
                        "standard",
                        2,
                        100_000,
                        "Research",
                        "{\"prompt\":\"Investigate the issue\"}");

        String previousOrgId = UUID.randomUUID().toString();
        TenantContextHolder.setOrgId(previousOrgId);
        var result = executor.execute(request);

        assertThat(result.outputJson()).contains("durable research result");
        assertThat(TenantContextHolder.getOrgId()).isEqualTo(previousOrgId);
        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(parent).createSubagentIfPresent(eq("researcher"), context.capture());
        assertThat(context.getValue().getSessionId()).isEqualTo("sub-session-1");
        assertThat(context.getValue().getUserId()).isEqualTo(userId.toString());
        String contextAgentRunId =
                context.getValue().get(RunOrchestrationService.ATTR_AGENT_RUN_ID);
        assertThat(contextAgentRunId).isEqualTo(agentRunId.toString());
        assertThat(TenantContext.from(context.getValue()).orgId()).isEqualTo(orgId.toString());
        verify(child).call(anyList(), eq(context.getValue()));
    }

    @Test
    void persistsFinalCoordinatorContinuationExactlyOnceByRun() throws Exception {
        HarnessAgent parent = mock(HarnessAgent.class);
        Msg finalReply =
                Msg.builder().role(MsgRole.ASSISTANT).textContent("final durable answer").build();
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(finalReply));
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        UUID runId = UUID.randomUUID();
        when(orchestration.hasUnsettledChildren(runId)).thenReturn(false);
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent, new ObjectMapper(), properties, chatPersistence, orchestration);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        orgId,
                        runId,
                        UUID.randomUUID(),
                        userId,
                        agentId,
                        sessionId,
                        UUID.randomUUID(),
                        "assistant",
                        null,
                        "member",
                        "standard",
                        2,
                        100_000,
                        "Continue coordinator",
                        "{\"continuation\":true,\"prompt\":\"Use child results\"}");

        var result = executor.execute(request);

        assertThat(result.outputJson()).contains("final durable answer");
        verify(chatPersistence)
                .saveAssistantMessageForRun(
                        any(TenantContext.class),
                        eq(sessionId),
                        eq(agentId),
                        eq(runId),
                        eq(finalReply.getContent()));
    }
}
