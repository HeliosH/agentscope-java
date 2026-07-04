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

import io.agentscope.saas.core.persistence.entity.FileEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for logical workspace file metadata. */
public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    Optional<FileEntity> findByOrgIdAndUserIdAndLogicalPath(
            UUID orgId, UUID userId, String logicalPath);

    List<FileEntity> findByOrgIdAndUserIdAndStatusOrderByLogicalPathAsc(
            UUID orgId, UUID userId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT f FROM FileEntity f
            WHERE f.orgId = :orgId
              AND f.userId = :userId
              AND f.logicalPath = :logicalPath
            """)
    Optional<FileEntity> lockByOrgUserPath(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("logicalPath") String logicalPath);
}
