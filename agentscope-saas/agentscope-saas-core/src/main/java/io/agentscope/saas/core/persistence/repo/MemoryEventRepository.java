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

import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for the durable memory event ledger. */
public interface MemoryEventRepository extends JpaRepository<MemoryEventEntity, UUID> {

    List<MemoryEventEntity> findTop100BySyncStatusOrderByCreatedAtAsc(String syncStatus);

    List<MemoryEventEntity> findByOrgIdAndUserIdOrderByCreatedAtDesc(UUID orgId, UUID userId);

    @Query(
            """
            SELECT e FROM MemoryEventEntity e
             WHERE e.orgId = :orgId
               AND (:userId IS NULL OR e.userId = :userId)
               AND (:sessionId IS NULL OR e.sessionId = :sessionId)
               AND (:syncStatus IS NULL OR e.syncStatus = :syncStatus)
             ORDER BY e.createdAt DESC
            """)
    List<MemoryEventEntity> findAdminEvents(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") String sessionId,
            @Param("syncStatus") String syncStatus,
            Pageable pageable);
}
