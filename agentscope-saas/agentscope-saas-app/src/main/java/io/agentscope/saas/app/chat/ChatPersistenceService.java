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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final ObjectMapper objectMapper;

    public ChatPersistenceService(
            AgentRepository agentRepository,
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
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
                            entity.setBuiltin(false);
                            entity.setUpdatedAt(OffsetDateTime.now());
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
        entity.setLabel(truncate(firstMessage, 255));
        entity.setMessageCount(0);
        entity.setSource("user");
        entity.setUnread(false);
        entity.setUpdatedAt(OffsetDateTime.now());
        return sessionRepository.save(entity);
    }

    /** Persists a user message and bumps the session's message count/timestamp. */
    @Transactional
    public ChatMessageEntity saveUserMessage(
            TenantContext tenant, UUID sessionId, UUID agentId, String content) {
        ChatMessageEntity msg =
                newMessage(
                        tenant,
                        sessionId,
                        agentId,
                        "user",
                        List.of(TextBlock.builder().text(content == null ? "" : content).build()));
        ChatMessageEntity saved = messageRepository.save(msg);
        touchSession(saved.getSessionId(), content);
        return saved;
    }

    /**
     * Persists the final assistant reply and bumps the session's message count/timestamp.
     *
     * @param blocks the structured content blocks (text + tool calls + reasoning) captured from the
     *     agent's terminal {@code AgentResultEvent}; serialized to {@code content_json} so the
     *     history can be faithfully replayed.
     */
    @Transactional
    public ChatMessageEntity saveAssistantMessage(
            TenantContext tenant, UUID sessionId, UUID agentId, List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        if (blocks.stream().allMatch(b -> b instanceof TextBlock t && t.getText().isEmpty())) {
            return null;
        }
        ChatMessageEntity msg = newMessage(tenant, sessionId, agentId, "assistant", blocks);
        ChatMessageEntity saved = messageRepository.save(msg);
        touchSession(saved.getSessionId(), extractText(blocks));
        return saved;
    }

    /** Persists one idempotent terminal assistant reply for a durable Run. */
    @Transactional
    public ChatMessageEntity saveAssistantMessageForRun(
            TenantContext tenant,
            UUID sessionId,
            UUID agentId,
            UUID runId,
            List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        sessionRepository
                .lockById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        ChatMessageEntity existing = messageRepository.findBySourceRunId(runId).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (blocks.stream().allMatch(b -> b instanceof TextBlock t && t.getText().isEmpty())) {
            return null;
        }
        ChatMessageEntity msg = newMessage(tenant, sessionId, agentId, "assistant", blocks);
        msg.setSourceRunId(runId);
        ChatMessageEntity saved = messageRepository.save(msg);
        touchSession(saved.getSessionId(), extractText(blocks));
        return saved;
    }

    private static String extractText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Soft-resets a session: deletes all its messages and zeroes the bookkeeping fields, keeping the
     * session row. The caller has already verified the session belongs to the (org, user, agent)
     * triple; this method performs the destructive writes inside a transaction so the derived
     * {@code deleteBySessionId} query (which requires an active transaction) succeeds on the
     * boundedElastic worker.
     */
    @Transactional
    public void resetSession(UUID sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository
                .findById(sessionId)
                .ifPresent(
                        s -> {
                            s.setMessageCount(0);
                            s.setLastMessage(null);
                            s.setUnread(false);
                            sessionRepository.save(s);
                        });
    }

    /**
     * Hard-deletes a session and all of its messages. Wrapped in a transaction for the same reason as
     * {@link #resetSession(UUID)}: the derived delete query needs a transaction to run on
     * boundedElastic.
     */
    @Transactional
    public void deleteSession(UUID sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    /**
     * Cascades an agent's deletion to its sessions and messages, then deletes the agent itself. All
     * writes run in one transaction so the per-session {@code deleteBySessionId} derived queries
     * succeed on boundedElastic. The per-user workspace is intentionally NOT touched (shared across
     * the user's agents). The caller is responsible for the org/owner authorization check on the
     * agent; this method trusts the entity it is handed.
     */
    @Transactional
    public void deleteAgentCascade(AgentEntity agent) {
        sessionRepository
                .findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
                        agent.getOrgId(), agent.getUserId(), agent.getId())
                .forEach(
                        s -> {
                            messageRepository.deleteBySessionId(s.getId());
                            sessionRepository.delete(s);
                        });
        agentRepository.delete(agent);
    }

    private ChatMessageEntity newMessage(
            TenantContext tenant,
            UUID sessionId,
            UUID agentId,
            String role,
            List<ContentBlock> blocks) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setOrgId(UUID.fromString(tenant.orgId()));
        msg.setUserId(UUID.fromString(tenant.userId()));
        msg.setSessionId(sessionId);
        msg.setAgentId(agentId);
        msg.setSeq(nextMessageSeq(sessionId));
        msg.setRole(role);
        msg.setContentJson(serializeBlocks(blocks));
        return msg;
    }

    private long nextMessageSeq(UUID sessionId) {
        sessionRepository
                .lockById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        return messageRepository.maxSeq(sessionId) + 1L;
    }

    private String serializeBlocks(List<ContentBlock> blocks) {
        try {
            return objectMapper.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize content blocks", e);
        }
    }

    private void touchSession(UUID sessionId, String lastMessagePreview) {
        sessionRepository
                .findById(sessionId)
                .ifPresent(
                        s -> {
                            s.setMessageCount(
                                    (s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
                            s.setUpdatedAt(OffsetDateTime.now());
                            if (lastMessagePreview != null) {
                                s.setLastMessage(truncate(lastMessagePreview, 2000));
                            }
                            // New activity since the user's last view → flag the inbox row.
                            s.setUnread(true);
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
