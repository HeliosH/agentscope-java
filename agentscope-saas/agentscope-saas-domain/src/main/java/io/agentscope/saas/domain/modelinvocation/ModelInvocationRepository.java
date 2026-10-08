/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.modelinvocation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Authoritative call receipts, quota reservations and per-organization purpose policy. */
public interface ModelInvocationRepository {
    record Scope(
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner) {
        public Scope {
            Objects.requireNonNull(orgId, "orgId");
            Objects.requireNonNull(userId, "userId");
        }
    }

    record Totals(long tokens, long costMicros, long calls) {}

    record NewInvocation(
            UUID id,
            Scope scope,
            String purpose,
            String modelId,
            String routeVersion,
            long reservedTokens,
            long reservedCostMicros,
            long estimatedInputTokens,
            OffsetDateTime deadlineAt,
            OffsetDateTime startedAt) {}

    record Receipt(
            UUID id,
            Scope scope,
            String purpose,
            String modelId,
            String routeVersion,
            String status,
            long reservedTokens,
            long reservedCostMicros,
            long estimatedInputTokens,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            String usageSource,
            OffsetDateTime deadlineAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String reasonCode) {}

    record Settlement(
            UUID orgId,
            UUID id,
            String status,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long costMicros,
            String usageSource,
            String reasonCode,
            OffsetDateTime finishedAt) {}

    record PurposePolicy(
            UUID orgId,
            String purpose,
            String modelId,
            int maxInputTokens,
            int maxOutputTokens,
            int timeoutSeconds,
            String version) {
        public PurposePolicy {
            Objects.requireNonNull(orgId, "orgId");
            if (!Set.of("REASONING", "COMPACTION", "MEMORY_EXTRACT", "MEMORY_CONSOLIDATE", "VERIFY")
                            .contains(purpose)
                    || maxInputTokens < 1
                    || maxOutputTokens < 1
                    || timeoutSeconds < 1
                    || version == null
                    || version.isBlank()
                    || version.length() > 64
                    || (modelId != null && (modelId.isBlank() || modelId.length() > 128))) {
                throw new IllegalArgumentException("Invalid model invocation purpose policy");
            }
        }
    }

    /** Locks organization then user, validating membership even on the privileged channel. */
    default boolean lockOwner(Scope scope) {
        return lockOwner(scope, true);
    }

    /** Settlement remains possible after an organization is deactivated. */
    boolean lockOwner(Scope scope, boolean requireActiveOrganization);

    boolean activeLease(Scope scope, OffsetDateTime now);

    Totals dailyUsage(Scope scope, boolean userOnly, OffsetDateTime since, OffsetDateTime now);

    Totals runReservations(Scope scope, boolean taskOnly, OffsetDateTime now);

    void insert(NewInvocation invocation);

    Optional<Receipt> find(UUID orgId, UUID id);

    record InvocationReference(UUID orgId, UUID id) {}

    /** Bounded system scan; individual settlement must revalidate ownership and status. */
    List<InvocationReference> findExpired(OffsetDateTime before, int limit);

    /** Exactly one transition from STARTED to a terminal state wins. */
    boolean settle(Settlement settlement);

    void recordMetric(Scope scope, String metric, long value, String modelId);

    Optional<PurposePolicy> policy(UUID orgId, String purpose);

    void savePolicy(PurposePolicy policy);
}
