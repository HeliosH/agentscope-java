/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable capacity reservations and fenced ownership of objects awaiting catalog publication. */
public interface FilePublicationRepository {
    record Execution(UUID taskId, UUID agentRunId, UUID attemptId, String leaseOwner) {
        public Execution {
            java.util.Objects.requireNonNull(taskId);
            java.util.Objects.requireNonNull(agentRunId);
            java.util.Objects.requireNonNull(attemptId);
            if (leaseOwner == null || leaseOwner.isBlank())
                throw new IllegalArgumentException("Missing publication lease owner");
        }
    }

    record Intent(
            UUID publicationId,
            UUID baseFileId,
            UUID baseVersionId,
            String baseStatus,
            String contentType,
            String source,
            String metadataJson,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner,
            boolean rawWriteRequired) {
        public Execution execution() {
            return attemptId == null
                    ? null
                    : new Execution(taskId, agentRunId, attemptId, leaseOwner);
        }
    }

    record Recovery(UUID recoveryToken, int recoveryAttempts, OffsetDateTime recoveryDeadlineAt) {}

    record Publication(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID runId,
            Long sessionGeneration,
            String logicalPath,
            String objectKey,
            String backend,
            String sha256,
            long sizeBytes,
            long reservedBytes,
            boolean ownsObject,
            String status,
            OffsetDateTime leaseUntil,
            OffsetDateTime eligibleAt,
            UUID claimToken,
            int attempts,
            UUID versionId) {}

    void stage(Publication publication);

    void saveIntent(
            Publication publication, Intent intent, boolean recoverable, OffsetDateTime deadline);

    Optional<Intent> intent(UUID orgId, UUID userId, UUID id);

    Optional<Recovery> recovery(UUID orgId, UUID userId, UUID id);

    boolean lockExecution(Publication publication, Intent intent, OffsetDateTime now);

    List<Publication> recoveryCandidates(OffsetDateTime now, int maxAttempts, int limit);

    int reclaimRecovery(
            UUID orgId,
            UUID userId,
            UUID id,
            UUID token,
            OffsetDateTime now,
            OffsetDateTime until,
            int maxAttempts);

    int publishRecovered(
            UUID orgId, UUID userId, UUID id, UUID token, UUID versionId, OffsetDateTime now);

    int deferRecovery(
            UUID orgId,
            UUID userId,
            UUID id,
            UUID token,
            OffsetDateTime now,
            OffsetDateTime retryAt);

    int abandonRecovery(UUID orgId, UUID userId, UUID id, UUID token, OffsetDateTime now);

    long reserved(UUID orgId, UUID userId, UUID excludingId, OffsetDateTime now);

    boolean pathBusy(UUID orgId, UUID userId, String path, OffsetDateTime now);

    int stored(UUID orgId, UUID userId, UUID id, OffsetDateTime now);

    Optional<Publication> lockOwned(UUID orgId, UUID userId, UUID id);

    int published(UUID orgId, UUID userId, UUID id, UUID versionId, OffsetDateTime now);

    int abort(UUID orgId, UUID userId, UUID id, OffsetDateTime eligibleAt);

    List<Publication> candidates(OffsetDateTime now, int maxAttempts, int limit);

    int claim(UUID id, UUID token, OffsetDateTime now, OffsetDateTime until, int maxAttempts);

    long references(UUID id);

    int collected(UUID id, UUID token, OffsetDateTime recheckAt);

    int failed(UUID id, UUID token, OffsetDateTime retryAt);
}
