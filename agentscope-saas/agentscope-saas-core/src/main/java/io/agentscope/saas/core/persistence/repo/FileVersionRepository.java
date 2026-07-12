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

import io.agentscope.saas.core.persistence.entity.FileVersionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for immutable workspace file versions. */
public interface FileVersionRepository extends JpaRepository<FileVersionEntity, UUID> {

    Optional<FileVersionEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<FileVersionEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId);

    Optional<FileVersionEntity> findFirstByFileIdOrderByVersionNoDesc(UUID fileId);

    List<FileVersionEntity> findByFileIdAndOrgIdAndUserIdOrderByVersionNoDesc(
            UUID fileId, UUID orgId, UUID userId);

    @Query(
            """
            SELECT COALESCE(MAX(v.versionNo), 0)
            FROM FileVersionEntity v
            WHERE v.fileId = :fileId
            """)
    long maxVersionNo(@Param("fileId") UUID fileId);

    @Query(
            """
            SELECT COALESCE(SUM(v.sizeBytes), 0)
            FROM FileEntity f JOIN FileVersionEntity v ON v.id = f.currentVersionId
            WHERE f.orgId = :orgId
              AND f.userId = :userId
              AND f.status = 'active'
            """)
    long currentUsageByUser(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Query(
            """
            SELECT COALESCE(SUM(v.sizeBytes), 0)
            FROM FileEntity f JOIN FileVersionEntity v ON v.id = f.currentVersionId
            WHERE f.orgId = :orgId
              AND f.status = 'active'
            """)
    long currentUsageByOrg(@Param("orgId") UUID orgId);
}
