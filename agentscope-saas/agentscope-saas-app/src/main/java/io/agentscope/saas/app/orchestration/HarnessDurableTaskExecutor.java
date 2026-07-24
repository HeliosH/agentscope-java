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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.config.TenantRlsWebFilter;
import io.agentscope.saas.app.workspace.WorkspaceProjectionCatalogSink;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.orchestration.DurableTaskExecutor;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Executes a durable task through the existing HarnessAgent with a reconstructed tenant context. */
@Component
public class HarnessDurableTaskExecutor implements DurableTaskExecutor {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;
    private final ChatPersistenceService chatPersistence;
    private final RunOrchestrationService orchestration;

    public HarnessDurableTaskExecutor(
            HarnessAgent agent,
            ObjectMapper objectMapper,
            SaasProperties properties,
            ChatPersistenceService chatPersistence,
            RunOrchestrationService orchestration) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatPersistence = chatPersistence;
        this.orchestration = orchestration;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        String previousOrgId = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(request.orgId().toString());
        try {
            return executeWithTenant(request);
        } finally {
            TenantContextHolder.setOrgId(previousOrgId);
        }
    }

    private ExecutionResult executeWithTenant(ExecutionRequest request) throws Exception {
        TenantContext tenant =
                new TenantContext(
                        request.orgId().toString(),
                        request.userId().toString(),
                        request.role(),
                        request.tier(),
                        request.maxSandboxes(),
                        request.tokenQuota());
        String executionSessionId =
                request.subSessionId() != null && !request.subSessionId().isBlank()
                        ? request.subSessionId()
                        : request.sessionId().toString();
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId(request.userId().toString())
                        .sessionId(executionSessionId)
                        .put(
                                WorkspaceProjectionCatalogSink.ATTR_AGENT_ID,
                                request.agentId().toString())
                        .put(RunOrchestrationService.ATTR_RUN_ID, request.runId().toString())
                        .put(
                                RunOrchestrationService.ATTR_AGENT_RUN_ID,
                                request.agentRunId() != null
                                        ? request.agentRunId().toString()
                                        : null)
                        .put(TenantContext.class, tenant)
                        .put(TenantContext.ATTR_KEY, tenant)
                        .build();
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(request.userId().toString())
                        .textContent(prompt(request))
                        .build();
        long timeout =
                Math.max(1L, properties.getOrchestration().getWorkerExecutionTimeoutSeconds());
        Msg result = executeAgent(request, input, context, timeout);
        if (result == null) {
            throw new IllegalStateException("HarnessAgent completed without a result message");
        }
        if (isContinuation(request) && !orchestration.hasUnsettledChildren(request.runId())) {
            chatPersistence.saveAssistantMessageForRun(
                    tenant,
                    request.sessionId(),
                    request.agentId(),
                    request.runId(),
                    result.getContent());
        }
        return new ExecutionResult(
                objectMapper.writeValueAsString(
                        Map.of("status", "succeeded", "summary", result.getTextContent())));
    }

    private Msg executeAgent(
            ExecutionRequest request, Msg input, RuntimeContext context, long timeoutSeconds) {
        Mono<Msg> execution;
        if (request.agentType() == null || "assistant".equals(request.agentType())) {
            execution = agent.call(input, context);
        } else {
            Agent child =
                    agent.createSubagentIfPresent(request.agentType(), context)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown durable subagent: "
                                                            + request.agentType()));
            if (child instanceof HarnessAgent harness) {
                execution = harness.call(input, context);
            } else if (child instanceof ReActAgent react) {
                execution = react.call(List.of(input), context);
            } else {
                execution = child.call(List.of(input));
            }
        }
        return execution
                .contextWrite(
                        reactorContext ->
                                reactorContext.put(
                                        TenantRlsWebFilter.ORG_ID_KEY, request.orgId().toString()))
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    private String prompt(ExecutionRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.inputJson());
            if (root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            for (String field : new String[] {"prompt", "message", "task"}) {
                String value = root.path(field).asText("").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // The scheduler validates JSON at write time; title is a safe fallback for old rows.
        }
        return request.title();
    }

    private boolean isContinuation(ExecutionRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.inputJson());
            if (root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            return root.path("continuation").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
