/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for durable subagent task discovery and delivery. */
public interface DurableTaskRepository {

    Optional<DurableTask> findLatest(TaskScope scope, String externalTaskId);

    List<DurableTask> findAll(TaskScope scope);

    List<DurableTask> findPendingDeliveries(TaskScope scope);

    void markDelivered(TaskScope scope, String externalTaskId, OffsetDateTime deliveredAt);

    record TaskScope(UUID orgId, UUID userId, UUID sessionId) {}
}
