/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.ChatSessionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for the chat Session aggregate root. */
public interface ChatSessionRepository {

    List<ChatSessionEntity> findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
            UUID orgId, UUID userId, UUID agentId);

    Optional<ChatSessionEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId);

    Optional<ChatSessionEntity> findByIdAndOrgIdAndUserIdAndAgentId(
            UUID id, UUID orgId, UUID userId, UUID agentId);

    Optional<ChatSessionEntity> findFirstByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
            UUID orgId, UUID userId, UUID agentId);

    Optional<ChatSessionEntity> lockById(UUID id);

    Optional<ChatSessionEntity> findById(UUID id);

    ChatSessionEntity save(ChatSessionEntity session);

    void delete(ChatSessionEntity session);

    void deleteById(UUID id);
}
