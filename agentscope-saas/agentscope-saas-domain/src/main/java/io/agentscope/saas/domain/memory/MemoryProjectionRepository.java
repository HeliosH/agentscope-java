/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Persistence port for projecting the durable memory ledger into Mem0. */
public interface MemoryProjectionRepository {

    List<MemoryProjectionEvent> findReplayable(
            int batchSize, OffsetDateTime now, OffsetDateTime legacyStaleBefore);

    boolean claim(
            MemoryProjectionEvent event,
            UUID token,
            int maxAttempts,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            OffsetDateTime legacyStaleBefore);

    boolean markSynced(UUID orgId, UUID id, UUID token, OffsetDateTime syncedAt);

    boolean markFailed(
            UUID orgId,
            UUID id,
            UUID token,
            String error,
            int maxAttempts,
            OffsetDateTime failedAt,
            OffsetDateTime nextAttemptAt);

    boolean exhaust(
            UUID orgId,
            UUID id,
            int maxAttempts,
            OffsetDateTime now,
            OffsetDateTime legacyStaleBefore);
}
