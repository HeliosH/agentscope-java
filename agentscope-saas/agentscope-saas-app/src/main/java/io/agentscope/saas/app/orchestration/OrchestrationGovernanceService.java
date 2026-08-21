/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.OrchestrationBudget;
import io.agentscope.saas.domain.orchestration.OrchestrationGovernanceRepository;
import io.agentscope.saas.domain.orchestration.OrchestrationGovernanceRepository.BudgetScope;
import io.agentscope.saas.orchestration.PermissionSnapshotIntegrity;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Atomic Run/Task budget accounting and permission-snapshot lookup. */
@Service
public class OrchestrationGovernanceService {

    private static final String BUDGET_EXCEEDED = "ORCHESTRATION_BUDGET_EXCEEDED";
    private final OrchestrationGovernanceRepository repository;
    private final TransactionOperations transactions;
    private final SaasProperties properties;

    public OrchestrationGovernanceService(
            OrchestrationGovernanceRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            SaasProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
    }

    public BudgetDecision preflight(UUID orgId, UUID runId, UUID agentRunId) {
        if (!properties.getOrchestration().isBudgetEnforcementEnabled()) {
            return BudgetDecision.allowed();
        }
        return transactions.execute(status -> evaluate(orgId, runId, agentRunId, 0, 0, 0, false));
    }

    public BudgetDecision consume(
            UUID orgId,
            UUID runId,
            UUID agentRunId,
            long inputTokens,
            long outputTokens,
            long totalTokens) {
        if (!properties.getOrchestration().isBudgetEnforcementEnabled()) {
            return BudgetDecision.allowed();
        }
        long normalizedTotal =
                Math.max(
                        Math.max(0, totalTokens),
                        safeAdd(Math.max(0, inputTokens), Math.max(0, outputTokens)));
        long costMicros =
                costMicros(
                        Math.max(0, inputTokens),
                        Math.max(0, outputTokens),
                        properties.getOrchestration().getInputTokenCostMicrosPerMillion(),
                        properties.getOrchestration().getOutputTokenCostMicrosPerMillion());
        return transactions.execute(
                status -> evaluate(orgId, runId, agentRunId, normalizedTotal, costMicros, 1, true));
    }

    public Optional<Duration> remainingTime(UUID orgId, UUID runId, UUID agentRunId) {
        Optional<OffsetDateTime> deadline =
                repository.findEffectiveDeadline(orgId, runId, agentRunId);
        if (deadline.isEmpty() || deadline.get() == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(OffsetDateTime.now(), deadline.get());
        return Optional.of(
                remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining);
    }

    /** Terminates queued or stalled work whose persisted Run/Task deadline has elapsed. */
    public int expireDue(int requestedLimit) {
        if (!properties.getOrchestration().isBudgetEnforcementEnabled()) {
            return 0;
        }
        int limit = Math.max(1, requestedLimit);
        var scopes = repository.findExpiredScopes(limit);
        int expired = 0;
        for (BudgetScope scope : scopes) {
            BudgetDecision decision =
                    transactions.execute(
                            status ->
                                    evaluate(
                                            scope.orgId(),
                                            scope.runId(),
                                            scope.agentRunId(),
                                            0,
                                            0,
                                            0,
                                            false));
            if (decision != null
                    && !decision.permitted()
                    && decision.reason() != null
                    && decision.reason().endsWith("_DEADLINE_EXCEEDED")) {
                expired++;
            }
        }
        return expired;
    }

    public PermissionSnapshot permissionSnapshot(UUID orgId, UUID runId, UUID agentRunId) {
        var persisted =
                repository
                        .findPermissionSnapshot(orgId, runId, agentRunId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Agent permission snapshot was not found"));
        PermissionSnapshot snapshot = new PermissionSnapshot(persisted.json(), persisted.hash());
        String json = snapshot.json() == null || snapshot.json().isBlank() ? "{}" : snapshot.json();
        PermissionSnapshotIntegrity.Snapshot canonical =
                PermissionSnapshotIntegrity.canonicalize(json);
        if (snapshot.hash() != null && !snapshot.hash().equals(canonical.hash())) {
            throw new IllegalStateException("Agent permission snapshot integrity check failed");
        }
        return new PermissionSnapshot(canonical.json(), canonical.hash());
    }

    /** Persists an immutable model/tool capability snapshot for audit and deterministic replay. */
    public void saveRuntimeCapabilitySnapshot(
            UUID orgId, UUID runId, UUID agentRunId, String snapshotJson, String snapshotHash) {
        if (snapshotJson == null
                || snapshotJson.isBlank()
                || snapshotHash == null
                || snapshotHash.length() != 64) {
            throw new IllegalArgumentException(
                    "A canonical runtime capability snapshot is required");
        }
        Boolean saved =
                transactions.execute(
                        status ->
                                repository.saveRuntimeCapabilitySnapshot(
                                        orgId,
                                        runId,
                                        agentRunId,
                                        snapshotJson,
                                        snapshotHash,
                                        OffsetDateTime.now()));
        if (!Boolean.TRUE.equals(saved)) {
            throw new IllegalStateException(
                    "Runtime capability snapshot is missing or changed within one Agent Run");
        }
    }

    private BudgetDecision evaluate(
            UUID orgId,
            UUID runId,
            UUID agentRunId,
            long tokenDelta,
            long costDelta,
            int modelCallDelta,
            boolean consume) {
        OrchestrationBudget row = repository.lockBudget(orgId, runId, agentRunId);
        if (!"RUNNING".equals(row.runStatus())) {
            return BudgetDecision.rejected("RUN_NOT_ACTIVE", "Run is no longer active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String reason = exceededReason(row, now, tokenDelta, costDelta, modelCallDelta, consume);
        if (reason != null) {
            if (consume) {
                recordUsage(row, now, tokenDelta, costDelta, modelCallDelta);
            }
            terminate(row, now, reason);
            return BudgetDecision.rejected(reason, message(reason));
        }
        if (consume) {
            recordUsage(row, now, tokenDelta, costDelta, modelCallDelta);
        }
        return BudgetDecision.allowed();
    }

    private void recordUsage(
            OrchestrationBudget row,
            OffsetDateTime now,
            long tokenDelta,
            long costDelta,
            int modelCallDelta) {
        repository.recordUsage(row, now, tokenDelta, costDelta, modelCallDelta);
    }

    private static String exceededReason(
            OrchestrationBudget row,
            OffsetDateTime now,
            long tokenDelta,
            long costDelta,
            int modelCallDelta,
            boolean consume) {
        if (expired(row.runDeadline(), now)) {
            return "RUN_DEADLINE_EXCEEDED";
        }
        if (expired(row.taskDeadline(), now)) {
            return "TASK_DEADLINE_EXCEEDED";
        }
        if (exceeds(row.runTokenBudget(), row.runConsumedTokens(), tokenDelta, consume)) {
            return "RUN_TOKEN_BUDGET_EXCEEDED";
        }
        if (exceeds(row.taskTokenBudget(), row.taskConsumedTokens(), tokenDelta, consume)) {
            return "TASK_TOKEN_BUDGET_EXCEEDED";
        }
        if (exceeds(row.runCostBudget(), row.runConsumedCost(), costDelta, consume)) {
            return "RUN_COST_BUDGET_EXCEEDED";
        }
        if (exceeds(row.taskCostBudget(), row.taskConsumedCost(), costDelta, consume)) {
            return "TASK_COST_BUDGET_EXCEEDED";
        }
        if (exceeds(row.runCallBudget(), row.runConsumedCalls(), modelCallDelta, consume)) {
            return "RUN_MODEL_CALL_BUDGET_EXCEEDED";
        }
        if (exceeds(row.taskCallBudget(), row.taskConsumedCalls(), modelCallDelta, consume)) {
            return "TASK_MODEL_CALL_BUDGET_EXCEEDED";
        }
        return null;
    }

    private void terminate(OrchestrationBudget row, OffsetDateTime now, String reason) {
        String message = message(reason);
        if (!repository.failRun(row, now, reason, message)) {
            return;
        }
        repository.failOutstandingWork(row, now, reason, message);
        appendEvent(row, reason, now);
    }

    private void appendEvent(OrchestrationBudget row, String reason, OffsetDateTime now) {
        long seq = repository.nextEventSequence(row.runId(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("message", message(reason));
        payload.put("taskId", row.taskId().toString());
        String payloadJson = JsonUtils.getJsonCodec().toJson(payload);
        UUID eventId = UUID.randomUUID();
        repository.appendBudgetExceededEvent(eventId, row, seq, payloadJson);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", row.runId().toString());
        envelope.put("seq", seq);
        envelope.put("taskId", row.taskId().toString());
        envelope.put("payload", payload);
        repository.appendBudgetExceededOutbox(
                UUID.randomUUID(), row, JsonUtils.getJsonCodec().toJson(envelope));
    }

    private static boolean expired(OffsetDateTime deadline, OffsetDateTime now) {
        return deadline != null && !deadline.isAfter(now);
    }

    private static boolean exceeds(Long limit, long consumed, long delta, boolean consume) {
        return limit != null && (consume ? safeAdd(consumed, delta) > limit : consumed >= limit);
    }

    private static boolean exceeds(Integer limit, int consumed, int delta, boolean consume) {
        return limit != null && (consume ? (long) consumed + delta > limit : consumed >= limit);
    }

    private static long costMicros(long input, long output, long inputRate, long outputRate) {
        long numerator =
                safeAdd(
                        safeMultiply(input, Math.max(0, inputRate)),
                        safeMultiply(output, Math.max(0, outputRate)));
        return numerator == 0 ? 0 : 1 + ((numerator - 1) / 1_000_000L);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String message(String reason) {
        return "Execution stopped by orchestration governance: " + reason;
    }

    public record BudgetDecision(boolean permitted, String reason, String message) {
        static BudgetDecision allowed() {
            return new BudgetDecision(true, null, null);
        }

        static BudgetDecision rejected(String reason, String message) {
            return new BudgetDecision(false, reason, message);
        }
    }

    public record PermissionSnapshot(String json, String hash) {}
}
