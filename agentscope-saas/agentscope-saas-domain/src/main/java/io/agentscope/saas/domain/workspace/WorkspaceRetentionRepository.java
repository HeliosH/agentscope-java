/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRetentionRepository {
    record Binding(UUID trackingId, UUID sandboxLeaseId) {}

    Optional<Binding> binding(
            WorkspaceManifestRepository.Scope scope,
            String sessionId,
            String providerId,
            String resourceId);

    record Retention(
            UUID sourceId,
            UUID orgId,
            UUID userId,
            UUID manifestId,
            UUID trackingId,
            UUID sandboxLeaseId,
            String providerId,
            String resourceId,
            String status,
            OffsetDateTime deadlineAt,
            UUID token,
            OffsetDateTime leaseUntil) {}

    long countHeld(UUID orgId);

    void stage(Retention value);

    int ready(Retention value, OffsetDateTime now);

    Optional<Retention> owned(UUID orgId, UUID userId, UUID sourceId);

    List<Retention> due(OffsetDateTime now, int limit);

    int claim(Retention value, UUID token, OffsetDateTime now, OffsetDateTime until);

    void cleaned(Retention value, UUID token);
}
