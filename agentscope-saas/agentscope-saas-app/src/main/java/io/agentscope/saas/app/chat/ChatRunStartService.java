/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.chat;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.app.config.OrchestrationPolicyFactory;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.model.AgentEntity;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import io.agentscope.saas.domain.repository.AgentRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically resolves chat persistence and creates or reuses its durable Run. */
@Service
public class ChatRunStartService {

    private final ChatPersistenceService persistence;
    private final AgentRepository agentRepository;
    private final RunOrchestrationService orchestration;
    private final OrchestrationPolicyFactory policyFactory;

    public ChatRunStartService(
            ChatPersistenceService persistence,
            AgentRepository agentRepository,
            RunOrchestrationService orchestration,
            OrchestrationPolicyFactory policyFactory) {
        this.persistence = persistence;
        this.agentRepository = agentRepository;
        this.orchestration = orchestration;
        this.policyFactory = policyFactory;
    }

    /**
     * The agent-row lock serializes only the short start transaction. It prevents two requests with
     * the same request id from both passing the lookup before either Run is committed.
     */
    @Transactional
    public StartedRun start(
            TenantContext tenant,
            String requestedAgentId,
            String requestedSessionId,
            String message,
            String requestId,
            String executionMessage,
            String modelId,
            List<ChatPersistenceService.AttachmentInput> attachments) {
        AgentEntity resolved = persistence.resolveAgent(tenant, requestedAgentId);
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID userId = UUID.fromString(tenant.userId());
        AgentEntity locked =
                agentRepository
                        .lockOwnedAgent(resolved.getId(), orgId, userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Agent disappeared during Run start"));

        var existing = orchestration.findByIdempotencyKey(tenant, locked.getId(), requestId);
        if (existing.isPresent()) {
            var run = existing.get();
            return new StartedRun(
                    locked.getId(),
                    run.sessionId(),
                    null,
                    run.id(),
                    null,
                    null,
                    null,
                    run.status(),
                    true);
        }

        ChatSessionEntity session =
                persistence.resolveSession(tenant, locked.getId(), requestedSessionId, message);
        var userMessage =
                persistence.saveUserMessage(
                        tenant, session.getId(), locked.getId(), message, attachments);
        var run =
                orchestration.createDirectRun(
                        tenant,
                        locked.getId(),
                        session.getId(),
                        userMessage.getId(),
                        message,
                        requestId,
                        policyFactory.runPolicy(),
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        java.util.Map.of(
                                                "prompt",
                                                executionMessage,
                                                "_runtime",
                                                java.util.Map.of(
                                                        "directChat", true, "modelId", modelId))));
        return new StartedRun(
                locked.getId(),
                session.getId(),
                userMessage.getId(),
                run.runId(),
                run.rootAgentRunId(),
                run.rootTaskId(),
                run.rootAttemptId(),
                RunOrchestrationService.RUN_RUNNING,
                run.reused());
    }

    /** Resolves a paused direct Run for a HITL continuation without creating another Run. */
    @Transactional
    public StartedRun resume(
            TenantContext tenant,
            String requestedAgentId,
            String requestedSessionId,
            String requestedRunId) {
        if (requestedRunId == null || requestedRunId.isBlank()) {
            throw new IllegalArgumentException("runId is required for tool confirmation");
        }
        AgentEntity resolved = persistence.resolveAgent(tenant, requestedAgentId);
        UUID sessionId = parseUuid(requestedSessionId, "sessionId");
        UUID runId = parseUuid(requestedRunId, "runId");
        var handle =
                orchestration
                        .resumeDirectRun(tenant, resolved.getId(), sessionId, runId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Paused Run was not found or cannot be resumed"));
        return new StartedRun(
                handle.agentId(),
                handle.sessionId(),
                null,
                handle.runId(),
                handle.rootAgentRunId(),
                handle.rootTaskId(),
                handle.rootAttemptId(),
                RunOrchestrationService.RUN_RUNNING,
                false);
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for tool confirmation");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    public record StartedRun(
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            UUID runId,
            UUID rootAgentRunId,
            UUID rootTaskId,
            UUID rootAttemptId,
            String status,
            boolean reused) {}
}
