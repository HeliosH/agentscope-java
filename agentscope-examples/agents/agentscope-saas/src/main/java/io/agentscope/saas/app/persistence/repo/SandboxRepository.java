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
package io.agentscope.saas.app.persistence.repo;

import io.agentscope.saas.app.persistence.entity.SandboxEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link SandboxEntity}, with org/user-scoped quota queries. */
public interface SandboxRepository extends JpaRepository<SandboxEntity, UUID> {

    /** Counts active sandboxes for a given org/user (used for quota enforcement). */
    int countByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    /** Finds active sandboxes for a given org/user. */
    List<SandboxEntity> findByOrgIdAndUserIdAndStatus(UUID orgId, UUID userId, String status);

    /** Finds active sandboxes whose idle TTL has expired. */
    @Query("SELECT s FROM SandboxEntity s WHERE s.status = 'active' AND s.expiresAt < :now")
    List<SandboxEntity> findExpiredSandboxes(@Param("now") OffsetDateTime now);
}
