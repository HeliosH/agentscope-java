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

import io.agentscope.saas.core.persistence.entity.UserEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link UserEntity}. */
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByIdpSubject(String idpSubject);

    /**
     * Locks the tenant/user row as the quota serialization point for operations that create
     * tenant-scoped runtime resources. Locking a stable parent row avoids the "no active sandbox
     * rows yet" race that a pessimistic lock on the sandbox rows alone cannot prevent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.orgId = :orgId AND u.id = :userId")
    Optional<UserEntity> lockTenantUser(@Param("orgId") UUID orgId, @Param("userId") UUID userId);
}
