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
import java.util.Optional;
import java.util.UUID;

/** Persistence port for orchestration budgets, deadlines, and permission snapshots. */
public interface OrchestrationGovernanceRepository {

    Optional<OffsetDateTime> findEffectiveDeadline(UUID orgId, UUID runId, UUID agentRunId);

    List<BudgetScope> findExpiredScopes(int limit);

    Optional<PermissionSnapshot> findPermissionSnapshot(UUID orgId, UUID runId, UUID agentRunId);

    /** Stores the first capability snapshot and accepts only byte-identical later captures. */
    boolean saveRuntimeCapabilitySnapshot(
            UUID orgId,
            UUID runId,
            UUID agentRunId,
            String snapshotJson,
            String snapshotHash,
            OffsetDateTime capturedAt);

    OrchestrationBudget lockBudget(UUID orgId, UUID runId, UUID agentRunId);

    void recordUsage(
            OrchestrationBudget budget,
            OffsetDateTime now,
            long tokenDelta,
            long costDelta,
            int modelCallDelta);

    boolean failRun(OrchestrationBudget budget, OffsetDateTime now, String reason, String message);

    void failOutstandingWork(
            OrchestrationBudget budget, OffsetDateTime now, String reason, String message);

    long nextEventSequence(UUID runId, OffsetDateTime now);

    void appendBudgetExceededEvent(
            UUID eventId, OrchestrationBudget budget, long sequence, String payloadJson);

    void appendBudgetExceededOutbox(UUID outboxId, OrchestrationBudget budget, String envelopeJson);

    record BudgetScope(UUID orgId, UUID runId, UUID agentRunId) {}

    record PermissionSnapshot(String json, String hash) {}
}
