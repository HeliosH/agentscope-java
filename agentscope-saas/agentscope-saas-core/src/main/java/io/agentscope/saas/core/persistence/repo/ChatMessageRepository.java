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
package io.agentscope.saas.core.persistence.repo;

import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link ChatMessageEntity}, with session-scoped queries for history replay. */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    long countBySessionId(UUID sessionId);

    Optional<ChatMessageEntity> findBySourceRunId(UUID sourceRunId);

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<ChatMessageEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);

    @Query(
            """
            SELECT m FROM ChatMessageEntity m
            WHERE m.sessionId = :sessionId
              AND (:afterSeq IS NULL OR m.seq > :afterSeq)
            ORDER BY m.seq ASC
            """)
    List<ChatMessageEntity> pageAfterSeq(
            @Param("sessionId") UUID sessionId,
            @Param("afterSeq") Long afterSeq,
            Pageable pageable);

    @Query(
            """
            SELECT m FROM ChatMessageEntity m
            WHERE m.sessionId = :sessionId
              AND (:beforeSeq IS NULL OR m.seq < :beforeSeq)
            ORDER BY m.seq DESC
            """)
    List<ChatMessageEntity> pageBeforeSeq(
            @Param("sessionId") UUID sessionId,
            @Param("beforeSeq") Long beforeSeq,
            Pageable pageable);

    @Query(
            """
            SELECT COALESCE(MAX(m.seq), 0)
            FROM ChatMessageEntity m
            WHERE m.sessionId = :sessionId
            """)
    long maxSeq(@Param("sessionId") UUID sessionId);

    /** Deletes all messages belonging to a session (cascading delete on session removal). */
    void deleteBySessionId(UUID sessionId);
}
