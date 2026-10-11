/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.workspace;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Archive-only ownership. This port cannot grant model, tool or sandbox execution permission. */
public interface WorkspaceArchiveRepository {
    record Authority(UUID jobId, UUID token) {
        public Authority {
            java.util.Objects.requireNonNull(jobId);
            java.util.Objects.requireNonNull(token);
        }
    }

    record Job(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            long sessionGeneration,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String originalOwner,
            UUID manifestId,
            UUID bodyId,
            long bodyBytes,
            String status,
            UUID token,
            OffsetDateTime leaseUntil,
            OffsetDateTime deadlineAt,
            OffsetDateTime nextAttemptAt,
            int attempts,
            OffsetDateTime createdAt) {
        public Scope scope() {
            return new Scope(
                    orgId,
                    userId,
                    agentId,
                    sessionId,
                    sessionGeneration,
                    runId,
                    new Execution(taskId, agentRunId, attemptId, originalOwner));
        }
    }

    record Item(String logicalPath, UUID publicationId) {}

    List<Publication> materializedCandidates(Scope scope);

    void create(Job job, List<Item> items);

    default void createPrepared(Job job, List<Item> items, String resultJson) {
        throw new UnsupportedOperationException();
    }

    default boolean isPrepared(Job job) {
        return false;
    }

    default Optional<Job> findAttempt(Scope scope) {
        return Optional.empty();
    }

    default boolean lockPreparedScope(Job job) {
        return false;
    }

    default boolean activatePrepared(Job job, OffsetDateTime now) {
        return false;
    }

    default int settlePrepared(Job job, OffsetDateTime now) {
        return 0;
    }

    default int bindPublishedItems(Job job, OffsetDateTime now) {
        return 0;
    }

    default void createInline(Job job, List<Item> items, String resultJson) {
        throw new UnsupportedOperationException("Inline archive result storage unavailable");
    }

    default Optional<String> inlineResult(Job job) {
        return Optional.empty();
    }

    default long inlineBytes(UUID orgId, UUID userId) {
        return 0;
    }

    default int purgeInline(OffsetDateTime before, int limit) {
        return 0;
    }

    boolean transition(Job job, OffsetDateTime now);

    List<Job> candidates(OffsetDateTime now, int limit);

    boolean lockScope(Job job);

    Optional<Job> lockOwned(UUID orgId, UUID id);

    int claim(Job job, UUID token, OffsetDateTime now, OffsetDateTime until, int maxAttempts);

    boolean lockAuthority(Job job, Authority authority, OffsetDateTime now);

    List<Publication> publications(Job job);

    List<Item> items(Job job);

    int defer(Job job, UUID token, OffsetDateTime now, OffsetDateTime retryAt);

    int revoke(Job job, OffsetDateTime now);

    /** Administrative completion runs in the durable scheduler's own short transaction. */
    boolean lockCompletion(Job job, UUID token, OffsetDateTime now, int maxAttempts);

    boolean manifestReady(Job job);

    int finishAttempt(Job job, String status, String code, OffsetDateTime now);

    int finishJob(Job job, String status, OffsetDateTime now);
}
