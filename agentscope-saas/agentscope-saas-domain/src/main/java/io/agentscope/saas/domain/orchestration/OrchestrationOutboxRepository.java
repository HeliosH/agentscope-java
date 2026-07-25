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
import java.util.UUID;

/** Persistence port for lease-based orchestration event delivery. */
public interface OrchestrationOutboxRepository {

    List<OrchestrationOutboxMessage> findClaimable(
            OffsetDateTime now, int batchSize, int maxAttempts);

    boolean claim(
            UUID id,
            String workerId,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt,
            int maxAttempts);

    void markPublished(UUID id, String workerId, OffsetDateTime publishedAt);

    void markFailed(
            UUID id,
            String workerId,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime deadLetteredAt,
            String error);
}
