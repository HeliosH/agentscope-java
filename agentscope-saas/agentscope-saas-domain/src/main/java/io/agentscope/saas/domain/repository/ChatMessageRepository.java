/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.ChatMessageEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for messages owned by a chat session. */
public interface ChatMessageRepository {

    long countBySessionId(UUID sessionId);

    Optional<ChatMessageEntity> findBySourceRunId(UUID sourceRunId);

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<ChatMessageEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);

    List<ChatMessageEntity> pageAfterSeq(UUID sessionId, Long afterSeq, int limit);

    List<ChatMessageEntity> pageBeforeSeq(UUID sessionId, Long beforeSeq, int limit);

    long maxSeq(UUID sessionId);

    ChatMessageEntity save(ChatMessageEntity message);

    default ChatMessageEntity saveAndFlush(ChatMessageEntity message) {
        return save(message);
    }

    void deleteBySessionId(UUID sessionId);
}
