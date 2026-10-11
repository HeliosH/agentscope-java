/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Private immutable byte escrow; never an execution permission or a user file. */
public interface WorkspaceSourceRepository {
    record Source(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID manifestId,
            String objectKey,
            String backend,
            long sizeBytes,
            String sha256,
            String status,
            OffsetDateTime deadlineAt,
            OffsetDateTime eligibleAt,
            UUID token,
            OffsetDateTime leaseUntil,
            int attempts,
            OffsetDateTime createdAt) {}

    long reserved(UUID orgId);

    record Baseline(String logicalPath, UUID fileId, UUID versionId, String status) {}

    void stage(Source source, List<Baseline> baselines);

    List<Baseline> baselines(Source source);

    int retain(Source source, OffsetDateTime now);

    Optional<Source> lockOwned(UUID orgId, UUID userId, UUID manifestId);

    List<Source> candidates(OffsetDateTime now, int limit);

    int claim(Source source, UUID token, OffsetDateTime now, OffsetDateTime until);

    int collected(Source source, UUID token, OffsetDateTime retryAt);

    int failed(Source source, UUID token, OffsetDateTime retryAt);
}
