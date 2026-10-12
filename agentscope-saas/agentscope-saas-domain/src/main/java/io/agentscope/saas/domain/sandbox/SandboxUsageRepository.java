/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.sandbox;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Physical resource aggregate and independent call use references. */
public interface SandboxUsageRepository {
    record Scope(
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            String leaseOwner,
            UUID sessionId,
            long sessionGeneration) {}

    record Resource(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID runId,
            UUID sessionId,
            long sessionGeneration,
            String providerId,
            String deploymentKey,
            String slotKey,
            String externalId,
            String status,
            UUID releaseToken,
            OffsetDateTime releaseUntil,
            int releaseAttempts,
            OffsetDateTime createdAt) {}

    record Reference(
            UUID id,
            UUID resourceId,
            UUID orgId,
            UUID userId,
            UUID taskId,
            UUID attemptId,
            String leaseOwner,
            UUID parentId,
            String status,
            OffsetDateTime expiresAt) {}

    boolean authority(Scope scope);

    long countOccupied(UUID orgId, UUID userId);

    boolean managedBackend(UUID orgId, UUID userId, String provider, String externalId);

    boolean slotOccupied(UUID orgId, UUID userId, String provider, String deployment, String slot);

    void insert(Resource resource);

    Optional<Resource> lock(UUID orgId, UUID userId, UUID id);

    Optional<Resource> find(UUID orgId, UUID userId, UUID id);

    void insert(Reference reference);

    Optional<Reference> reference(UUID orgId, UUID userId, UUID id);

    long activeReferences(UUID resourceId, UUID exceptId);

    boolean activate(Resource resource, String externalId);

    boolean state(Resource resource, String state, UUID token, OffsetDateTime until);

    boolean heartbeat(Reference reference, OffsetDateTime expiresAt);

    boolean closeReference(Reference reference);

    boolean finish(UUID orgId, UUID resourceId, UUID token, boolean success);
}
