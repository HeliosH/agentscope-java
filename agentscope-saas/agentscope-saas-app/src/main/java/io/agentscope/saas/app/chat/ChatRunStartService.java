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

import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically resolves chat persistence and creates or reuses its durable Run. */
@Service
public class ChatRunStartService {

    private final ChatPersistenceService persistence;
    private final AgentRepository agentRepository;
    private final RunOrchestrationService orchestration;

    public ChatRunStartService(
            ChatPersistenceService persistence,
            AgentRepository agentRepository,
            RunOrchestrationService orchestration) {
        this.persistence = persistence;
        this.agentRepository = agentRepository;
        this.orchestration = orchestration;
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
            String requestId) {
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
                    locked.getId(), run.sessionId(), null, run.id(), null, run.status(), true);
        }

        ChatSessionEntity session =
                persistence.resolveSession(tenant, locked.getId(), requestedSessionId, message);
        var userMessage =
                persistence.saveUserMessage(tenant, session.getId(), locked.getId(), message);
        var run =
                orchestration.createDirectRun(
                        tenant,
                        locked.getId(),
                        session.getId(),
                        userMessage.getId(),
                        message,
                        requestId);
        return new StartedRun(
                locked.getId(),
                session.getId(),
                userMessage.getId(),
                run.runId(),
                run.rootAgentRunId(),
                RunOrchestrationService.RUN_RUNNING,
                run.reused());
    }

    public record StartedRun(
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            UUID runId,
            UUID rootAgentRunId,
            String status,
            boolean reused) {}
}
