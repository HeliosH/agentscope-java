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
            int batchSize, int maxAttempts, OffsetDateTime staleBefore);

    boolean claim(UUID id, int maxAttempts, OffsetDateTime staleBefore, OffsetDateTime claimedAt);

    void markSynced(UUID id, OffsetDateTime syncedAt);

    void markFailed(UUID id, String error, OffsetDateTime failedAt);
}
