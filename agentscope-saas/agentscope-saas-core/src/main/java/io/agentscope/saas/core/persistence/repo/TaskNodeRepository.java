/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.core.persistence.repo;

import io.agentscope.saas.core.persistence.entity.TaskNodeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task nodes are always loaded through their tenant-owned Run. */
public interface TaskNodeRepository extends JpaRepository<TaskNodeEntity, UUID> {

    List<TaskNodeEntity> findByRunIdAndOrgIdOrderByCreatedAtAsc(UUID runId, UUID orgId);

    Optional<TaskNodeEntity> findByRunIdAndOrgIdAndExternalTaskId(
            UUID runId, UUID orgId, String externalTaskId);
}
