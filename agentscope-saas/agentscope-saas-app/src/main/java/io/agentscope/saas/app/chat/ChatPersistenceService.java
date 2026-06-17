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
package io.agentscope.saas.app.chat;

import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists chat sessions and messages for the multi-user assistant (Phase A). Every operation is
 * scoped by the caller's {@link TenantContext} (org + user) so users can only touch their own data;
 * agent-id resolution auto-creates a per-user default agent so a freshly registered user can start
 * chatting without first creating an agent.
 */
@Service
public class ChatPersistenceService {

    /** Name of the auto-created default agent for users who never created one explicitly. */
    public static final String DEFAULT_AGENT_NAME = "assistant";

    private final AgentRepository agentRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatPersistenceService(
            AgentRepository agentRepository,
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * Resolves the agent to run. If {@code requestedAgentId} is provided and belongs to the caller,
     * use it; otherwise fall back to the caller's default agent, creating it on first use.
     */
    @Transactional
    public AgentEntity resolveAgent(TenantContext tenant, String requestedAgentId) {
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID userId = UUID.fromString(tenant.userId());
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            try {
                UUID id = UUID.fromString(requestedAgentId);
                AgentEntity found = agentRepository.findByIdAndOrgId(id, orgId).orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID — fall through to default agent.
            }
        }
        return agentRepository
                .findByOrgIdAndUserIdAndName(orgId, userId, DEFAULT_AGENT_NAME)
                .orElseGet(
                        () -> {
                            AgentEntity entity = new AgentEntity();
                            entity.setId(UUID.randomUUID());
                            entity.setOrgId(orgId);
                            entity.setUserId(userId);
                            entity.setName(DEFAULT_AGENT_NAME);
                            entity.setVisibility("private");
                            entity.setStatus("active");
                            return agentRepository.save(entity);
                        });
    }

    /**
     * Resolves the chat session. If {@code requestedSessionId} is provided and belongs to the
     * caller+agent, reuse it; otherwise create a new session titled with the first user message.
     */
    @Transactional
    public ChatSessionEntity resolveSession(
            TenantContext tenant, UUID agentId, String requestedSessionId, String firstMessage) {
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID userId = UUID.fromString(tenant.userId());
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            try {
                UUID id = UUID.fromString(requestedSessionId);
                ChatSessionEntity found =
                        sessionRepository
                                .findByIdAndOrgIdAndUserIdAndAgentId(id, orgId, userId, agentId)
                                .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID — create a new session.
            }
        }
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setUserId(userId);
        entity.setAgentId(agentId);
        entity.setTitle(truncate(firstMessage, 500));
        entity.setMessageCount(0);
        entity.setSource("user");
        entity.setUpdatedAt(OffsetDateTime.now());
        return sessionRepository.save(entity);
    }

    /** Persists a user message and bumps the session's message count/timestamp. */
    @Transactional
    public ChatMessageEntity saveUserMessage(
            TenantContext tenant, UUID sessionId, UUID agentId, String content) {
        ChatMessageEntity msg = newMessage(tenant, sessionId, agentId, "user", content);
        ChatMessageEntity saved = messageRepository.save(msg);
        touchSession(saved.getSessionId());
        return saved;
    }

    /** Persists the final assistant reply and bumps the session's message count/timestamp. */
    @Transactional
    public ChatMessageEntity saveAssistantMessage(
            TenantContext tenant, UUID sessionId, UUID agentId, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        ChatMessageEntity msg = newMessage(tenant, sessionId, agentId, "assistant", content);
        ChatMessageEntity saved = messageRepository.save(msg);
        touchSession(saved.getSessionId());
        return saved;
    }

    private ChatMessageEntity newMessage(
            TenantContext tenant, UUID sessionId, UUID agentId, String role, String content) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setOrgId(UUID.fromString(tenant.orgId()));
        msg.setUserId(UUID.fromString(tenant.userId()));
        msg.setSessionId(sessionId);
        msg.setAgentId(agentId);
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    private void touchSession(UUID sessionId) {
        sessionRepository
                .findById(sessionId)
                .ifPresent(
                        s -> {
                            s.setMessageCount(
                                    (s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
                            s.setUpdatedAt(OffsetDateTime.now());
                            sessionRepository.save(s);
                        });
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
