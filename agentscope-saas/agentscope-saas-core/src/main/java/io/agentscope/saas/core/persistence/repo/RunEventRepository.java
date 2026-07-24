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

import io.agentscope.saas.core.persistence.entity.RunEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ordered event stream repository for reconnecting Run consumers. */
public interface RunEventRepository extends JpaRepository<RunEventEntity, UUID> {

    long countByRunId(UUID runId);

    List<RunEventEntity> findByRunIdAndOrgIdAndUserIdAndSeqGreaterThanOrderBySeqAsc(
            UUID runId, UUID orgId, UUID userId, long afterSeq, Pageable pageable);
}
