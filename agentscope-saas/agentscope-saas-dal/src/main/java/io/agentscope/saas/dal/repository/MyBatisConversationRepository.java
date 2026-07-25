/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.ConversationMapper;
import io.agentscope.saas.domain.model.AgentEntity;
import io.agentscope.saas.domain.model.ChatMessageEntity;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import io.agentscope.saas.domain.repository.AgentRepository;
import io.agentscope.saas.domain.repository.ChatMessageRepository;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for Agent and Conversation domain ports. */
@Repository
public class MyBatisConversationRepository
        implements AgentRepository, ChatSessionRepository, ChatMessageRepository {

    private final ConversationMapper mapper;

    public MyBatisConversationRepository(ConversationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AgentEntity> findByOrgId(UUID orgId) {
        return mapper.findAgentsByOrg(orgId);
    }

    @Override
    public Optional<AgentEntity> findByIdAndOrgId(UUID id, UUID orgId) {
        return first(mapper.findAgent(id, orgId));
    }

    @Override
    public Optional<AgentEntity> lockOwnedAgent(UUID id, UUID orgId, UUID userId) {
        return first(mapper.lockOwnedAgent(id, orgId, userId));
    }

    @Override
    public List<AgentEntity> findByOrgIdAndUserIdOrderByIdAsc(UUID orgId, UUID userId) {
        return mapper.findOwnedAgentsById(orgId, userId);
    }

    @Override
    public List<AgentEntity> findByOrgIdAndUserIdOrderByUpdatedAtDesc(UUID orgId, UUID userId) {
        return mapper.findOwnedAgentsByUpdatedAt(orgId, userId);
    }

    @Override
    public Optional<AgentEntity> findByOrgIdAndUserIdAndName(UUID orgId, UUID userId, String name) {
        return first(mapper.findOwnedAgentByName(orgId, userId, name));
    }

    @Override
    public AgentEntity save(AgentEntity agent) {
        if (mapper.updateAgent(agent) == 0) {
            requireOne(mapper.insertAgent(agent), "insert Agent " + agent.getId());
        }
        return agent;
    }

    @Override
    public void delete(AgentEntity agent) {
        deleteByIdAndOrgId(agent.getId(), agent.getOrgId());
    }

    @Override
    public long deleteByIdAndOrgId(UUID id, UUID orgId) {
        return mapper.deleteAgent(id, orgId);
    }

    @Override
    public List<ChatSessionEntity> findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
            UUID orgId, UUID userId, UUID agentId) {
        return mapper.findSessions(orgId, userId, agentId);
    }

    @Override
    public Optional<ChatSessionEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId) {
        return first(mapper.findOwnedSession(id, orgId, userId));
    }

    @Override
    public Optional<ChatSessionEntity> findByIdAndOrgIdAndUserIdAndAgentId(
            UUID id, UUID orgId, UUID userId, UUID agentId) {
        return first(mapper.findOwnedAgentSession(id, orgId, userId, agentId));
    }

    @Override
    public Optional<ChatSessionEntity> findFirstByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
            UUID orgId, UUID userId, UUID agentId) {
        return first(mapper.findLatestSession(orgId, userId, agentId));
    }

    @Override
    public Optional<ChatSessionEntity> lockById(UUID id) {
        return first(mapper.lockSession(id));
    }

    @Override
    public Optional<ChatSessionEntity> findById(UUID id) {
        return first(mapper.findSession(id));
    }

    @Override
    public ChatSessionEntity save(ChatSessionEntity session) {
        if (mapper.updateSession(session) == 0) {
            requireOne(mapper.insertSession(session), "insert Session " + session.getId());
        }
        return session;
    }

    @Override
    public void delete(ChatSessionEntity session) {
        deleteById(session.getId());
    }

    @Override
    public void deleteById(UUID id) {
        mapper.deleteSession(id);
    }

    @Override
    public long countBySessionId(UUID sessionId) {
        return mapper.countMessages(sessionId);
    }

    @Override
    public Optional<ChatMessageEntity> findBySourceRunId(UUID sourceRunId) {
        return first(mapper.findMessageBySourceRun(sourceRunId));
    }

    @Override
    public List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId) {
        return mapper.findMessagesByCreatedAt(sessionId);
    }

    @Override
    public List<ChatMessageEntity> findBySessionIdOrderBySeqAsc(UUID sessionId) {
        return mapper.findMessagesBySeq(sessionId);
    }

    @Override
    public List<ChatMessageEntity> pageAfterSeq(UUID sessionId, Long afterSeq, int limit) {
        return mapper.pageMessagesAfter(sessionId, afterSeq, limit);
    }

    @Override
    public List<ChatMessageEntity> pageBeforeSeq(UUID sessionId, Long beforeSeq, int limit) {
        return mapper.pageMessagesBefore(sessionId, beforeSeq, limit);
    }

    @Override
    public long maxSeq(UUID sessionId) {
        return mapper.maxMessageSeq(sessionId);
    }

    @Override
    public ChatMessageEntity save(ChatMessageEntity message) {
        requireOne(mapper.insertMessage(message), "insert Message " + message.getId());
        return message;
    }

    @Override
    public void deleteBySessionId(UUID sessionId) {
        mapper.deleteMessages(sessionId);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
