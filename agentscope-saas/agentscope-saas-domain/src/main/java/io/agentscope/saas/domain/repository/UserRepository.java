/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.UserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for tenant user accounts. */
public interface UserRepository {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByIdpSubject(String idpSubject);

    List<UserEntity> findByOrgIdOrderByCreatedAtDesc(UUID orgId, int limit);

    Optional<UserEntity> findByOrgIdAndId(UUID orgId, UUID id);

    long countByOrgIdAndRole(UUID orgId, String role);

    Optional<UserEntity> lockTenantUser(UUID orgId, UUID userId);

    UserEntity save(UserEntity user);
}
