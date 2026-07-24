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

import io.agentscope.saas.core.persistence.entity.OrchestrationOutboxEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistent hand-off from state transactions to asynchronous event delivery. */
public interface OrchestrationOutboxRepository
        extends JpaRepository<OrchestrationOutboxEntity, UUID> {

    long countByAggregateId(UUID aggregateId);
}
