/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.AgentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for the Agent aggregate. */
public interface AgentRepository {

    List<AgentEntity> findByOrgId(UUID orgId);

    Optional<AgentEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<AgentEntity> lockOwnedAgent(UUID id, UUID orgId, UUID userId);

    List<AgentEntity> findByOrgIdAndUserIdOrderByIdAsc(UUID orgId, UUID userId);

    List<AgentEntity> findByOrgIdAndUserIdOrderByUpdatedAtDesc(UUID orgId, UUID userId);

    Optional<AgentEntity> findByOrgIdAndUserIdAndName(UUID orgId, UUID userId, String name);

    AgentEntity save(AgentEntity agent);

    void delete(AgentEntity agent);

    long deleteByIdAndOrgId(UUID id, UUID orgId);
}
