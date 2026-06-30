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

import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link SandboxEntity}, with org/user-scoped quota queries. */
public interface SandboxRepository extends JpaRepository<SandboxEntity, UUID> {

    interface SandboxPoolCount {
        String getSandboxType();

        String getStatus();

        long getCount();
    }

    interface SandboxTypeCount {
        String getSandboxType();

        long getCount();
    }

    /** Counts active sandboxes for a given org/user (used for quota enforcement). */
    int countByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    /** Finds active sandboxes for a given org/user. */
    List<SandboxEntity> findByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    /** Finds active sandboxes whose idle TTL has expired. */
    @Query("SELECT s FROM SandboxEntity s WHERE s.status = 'active' AND s.expiresAt < :now")
    List<SandboxEntity> findExpiredSandboxes(@Param("now") OffsetDateTime now);

    /** Counts sandbox tracking rows by backend type and lifecycle status for pool gauges. */
    @Query(
            """
            SELECT s.sandboxType AS sandboxType, s.status AS status, COUNT(s) AS count
            FROM SandboxEntity s
            GROUP BY s.sandboxType, s.status
            """)
    List<SandboxPoolCount> countBySandboxTypeAndStatus();

    /** Counts active sandbox tracking rows that are already past their lease expiry. */
    @Query(
            """
            SELECT s.sandboxType AS sandboxType, COUNT(s) AS count
            FROM SandboxEntity s
            WHERE s.status = 'active' AND s.expiresAt < :now
            GROUP BY s.sandboxType
            """)
    List<SandboxTypeCount> countExpiredActiveBySandboxType(@Param("now") OffsetDateTime now);

    /** Org-admin sandbox inventory with optional low-cardinality operational filters. */
    @Query(
            """
            SELECT s FROM SandboxEntity s
            WHERE s.orgId = :orgId
              AND (:userId IS NULL OR s.userId = :userId)
              AND (:status IS NULL OR s.status = :status)
              AND (:sandboxType IS NULL OR s.sandboxType = :sandboxType)
              AND (:expiredOnly = false OR (s.status = 'active' AND s.expiresAt < :now))
            ORDER BY s.lastUsedAt DESC, s.createdAt DESC
            """)
    List<SandboxEntity> findAdminSandboxes(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("sandboxType") String sandboxType,
            @Param("expiredOnly") boolean expiredOnly,
            @Param("now") OffsetDateTime now,
            Pageable pageable);
}
