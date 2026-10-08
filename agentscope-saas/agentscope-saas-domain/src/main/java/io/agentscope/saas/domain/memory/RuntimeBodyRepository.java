/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Staged immutable transcript objects and fenced orphan collection; no storage SDK dependencies. */
public interface RuntimeBodyRepository {
    record Body(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            String objectKey,
            String backend,
            String sha256,
            long sizeBytes,
            String status,
            OffsetDateTime eligibleAt,
            UUID claimToken,
            int attempts) {}

    void stage(Body body);

    int ready(
            RuntimeMessageRepository.Scope scope,
            UUID id,
            OffsetDateTime now,
            OffsetDateTime eligibleAt);

    int attach(RuntimeMessageRepository.Scope scope, UUID id, OffsetDateTime eligibleAt);

    Optional<Body> findOwned(RuntimeMessageRepository.Scope scope, UUID id);

    List<Body> candidates(OffsetDateTime now, int maxAttempts, int limit);

    int claim(UUID id, UUID token, OffsetDateTime now, OffsetDateTime leaseUntil, int maxAttempts);

    int collected(UUID id, UUID token, OffsetDateTime recheckAt);

    int failed(UUID id, UUID token, OffsetDateTime retryAt);

    long references(UUID id);

    int retain(UUID id, UUID token, OffsetDateTime eligibleAt);

    boolean lockQuota(RuntimeMessageRepository.Scope scope);

    long usage(UUID orgId, UUID userId);
}
