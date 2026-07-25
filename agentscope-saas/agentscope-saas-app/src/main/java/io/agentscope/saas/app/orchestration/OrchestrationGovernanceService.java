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
import io.agentscope.saas.orchestration.PermissionSnapshotIntegrity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic Run/Task budget accounting and permission-snapshot lookup. */
@Service
public class OrchestrationGovernanceService {

    private static final String BUDGET_EXCEEDED = "ORCHESTRATION_BUDGET_EXCEEDED";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SaasProperties properties;

    public OrchestrationGovernanceService(
            @Qualifier("adminDataSource") DataSource dataSource, SaasProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
        List<OffsetDateTime> deadlines =
                jdbc.query(
                        """
                        SELECT CASE
                                 WHEN r.deadline_at IS NULL THEN t.deadline_at
                                 WHEN t.deadline_at IS NULL THEN r.deadline_at
                                 WHEN r.deadline_at < t.deadline_at THEN r.deadline_at
                                 ELSE t.deadline_at
                               END AS effective_deadline
                          FROM assistant_runs r
                          JOIN agent_runs ar ON ar.run_id = r.id
                          JOIN task_nodes t ON t.id = ar.task_id
                         WHERE r.id = ? AND r.org_id = ? AND ar.id = ?
                        """,
                        (rs, rowNum) -> rs.getObject("effective_deadline", OffsetDateTime.class),
                        runId,
                        orgId,
                        agentRunId);
        if (deadlines.isEmpty() || deadlines.get(0) == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(OffsetDateTime.now(), deadlines.get(0));
        return Optional.of(
                remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining);
    }

    /** Terminates queued or stalled work whose persisted Run/Task deadline has elapsed. */
    public int expireDue(int requestedLimit) {
        if (!properties.getOrchestration().isBudgetEnforcementEnabled()) {
            return 0;
        }
        int limit = Math.max(1, requestedLimit);
        List<BudgetScope> scopes =
                jdbc.query(
                        """
                        SELECT r.org_id, r.id AS run_id, ar.id AS agent_run_id
                          FROM assistant_runs r
                          JOIN task_nodes t ON t.run_id = r.id
                          JOIN agent_runs ar ON ar.task_id = t.id
                         WHERE r.status = 'RUNNING'
                           AND (r.deadline_at <= CURRENT_TIMESTAMP
                                OR t.deadline_at <= CURRENT_TIMESTAMP)
                         ORDER BY COALESCE(t.deadline_at, r.deadline_at), r.created_at
                         LIMIT ?
                        """,
                        (rs, rowNum) ->
                                new BudgetScope(
                                        rs.getObject("org_id", UUID.class),
                                        rs.getObject("run_id", UUID.class),
                                        rs.getObject("agent_run_id", UUID.class)),
                        limit);
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
        List<PermissionSnapshot> rows =
                jdbc.query(
                        """
                        SELECT permission_snapshot_json, permission_snapshot_hash
                          FROM agent_runs
                         WHERE id = ? AND run_id = ? AND org_id = ?
                        """,
                        (rs, rowNum) ->
                                new PermissionSnapshot(
                                        rs.getString("permission_snapshot_json"),
                                        rs.getString("permission_snapshot_hash")),
                        agentRunId,
                        runId,
                        orgId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Agent permission snapshot was not found");
        }
        PermissionSnapshot snapshot = rows.get(0);
        String json = snapshot.json() == null || snapshot.json().isBlank() ? "{}" : snapshot.json();
        PermissionSnapshotIntegrity.Snapshot canonical =
                PermissionSnapshotIntegrity.canonicalize(json);
        if (snapshot.hash() != null && !snapshot.hash().equals(canonical.hash())) {
            throw new IllegalStateException("Agent permission snapshot integrity check failed");
        }
        return new PermissionSnapshot(canonical.json(), canonical.hash());
    }

    private BudgetDecision evaluate(
            UUID orgId,
            UUID runId,
            UUID agentRunId,
            long tokenDelta,
            long costDelta,
            int modelCallDelta,
            boolean consume) {
        BudgetRow row = loadBudget(orgId, runId, agentRunId);
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
            BudgetRow row,
            OffsetDateTime now,
            long tokenDelta,
            long costDelta,
            int modelCallDelta) {
        jdbc.update(
                """
                UPDATE assistant_runs
                   SET consumed_tokens = consumed_tokens + ?,
                       consumed_cost_micros = consumed_cost_micros + ?,
                       consumed_model_calls = consumed_model_calls + ?,
                       updated_at = ?
                 WHERE id = ? AND org_id = ? AND status = 'RUNNING'
                """,
                tokenDelta,
                costDelta,
                modelCallDelta,
                now,
                row.runId(),
                row.orgId());
        jdbc.update(
                """
                UPDATE task_nodes
                   SET consumed_tokens = consumed_tokens + ?,
                       consumed_cost_micros = consumed_cost_micros + ?,
                       consumed_model_calls = consumed_model_calls + ?,
                       updated_at = ?
                 WHERE id = ? AND org_id = ?
                """,
                tokenDelta,
                costDelta,
                modelCallDelta,
                now,
                row.taskId(),
                row.orgId());
    }

    private BudgetRow loadBudget(UUID orgId, UUID runId, UUID agentRunId) {
        List<BudgetRow> rows =
                jdbc.query(
                        """
                        SELECT r.id AS run_id, r.org_id, r.status AS run_status,
                               r.token_budget AS run_token_budget,
                               r.consumed_tokens AS run_consumed_tokens,
                               r.cost_budget_micros AS run_cost_budget,
                               r.consumed_cost_micros AS run_consumed_cost,
                               r.model_call_budget AS run_call_budget,
                               r.consumed_model_calls AS run_consumed_calls,
                               r.deadline_at AS run_deadline,
                               t.id AS task_id, t.token_budget AS task_token_budget,
                               t.consumed_tokens AS task_consumed_tokens,
                               t.cost_budget_micros AS task_cost_budget,
                               t.consumed_cost_micros AS task_consumed_cost,
                               t.model_call_budget AS task_call_budget,
                               t.consumed_model_calls AS task_consumed_calls,
                               t.deadline_at AS task_deadline
                          FROM assistant_runs r
                          JOIN agent_runs ar ON ar.run_id = r.id
                          JOIN task_nodes t ON t.id = ar.task_id
                         WHERE r.id = ? AND r.org_id = ? AND ar.id = ?
                         FOR UPDATE
                        """,
                        this::mapBudget,
                        runId,
                        orgId,
                        agentRunId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Run budget scope was not found");
        }
        return rows.get(0);
    }

    private BudgetRow mapBudget(ResultSet rs, int rowNum) throws SQLException {
        return new BudgetRow(
                rs.getObject("run_id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("run_status"),
                nullableLong(rs, "run_token_budget"),
                rs.getLong("run_consumed_tokens"),
                nullableLong(rs, "run_cost_budget"),
                rs.getLong("run_consumed_cost"),
                nullableInt(rs, "run_call_budget"),
                rs.getInt("run_consumed_calls"),
                rs.getObject("run_deadline", OffsetDateTime.class),
                rs.getObject("task_id", UUID.class),
                nullableLong(rs, "task_token_budget"),
                rs.getLong("task_consumed_tokens"),
                nullableLong(rs, "task_cost_budget"),
                rs.getLong("task_consumed_cost"),
                nullableInt(rs, "task_call_budget"),
                rs.getInt("task_consumed_calls"),
                rs.getObject("task_deadline", OffsetDateTime.class));
    }

    private static String exceededReason(
            BudgetRow row,
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

    private void terminate(BudgetRow row, OffsetDateTime now, String reason) {
        String message = message(reason);
        int updated =
                jdbc.update(
                        """
                        UPDATE assistant_runs
                           SET status = 'FAILED', failure_code = ?, failure_message = ?,
                               completed_at = ?, updated_at = ?
                         WHERE id = ? AND org_id = ? AND status = 'RUNNING'
                        """,
                        reason,
                        message,
                        now,
                        now,
                        row.runId(),
                        row.orgId());
        if (updated != 1) {
            return;
        }
        jdbc.update(
                """
                UPDATE task_nodes
                   SET status = CASE WHEN id = ? THEN 'FAILED' ELSE 'CANCELLED' END,
                       last_error_code = CASE WHEN id = ? THEN ? ELSE last_error_code END,
                       last_error_message = CASE WHEN id = ? THEN ? ELSE last_error_message END,
                       completed_at = ?, updated_at = ?
                 WHERE run_id = ? AND status IN ('PENDING','READY','CLAIMED','RUNNING')
                """,
                row.taskId(),
                row.taskId(),
                reason,
                row.taskId(),
                message,
                now,
                now,
                row.runId());
        jdbc.update(
                """
                UPDATE agent_runs
                   SET status = CASE WHEN task_id = ? THEN 'FAILED' ELSE 'CANCELLED' END,
                       completed_at = ?, updated_at = ?
                 WHERE run_id = ? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')
                """,
                row.taskId(),
                now,
                now,
                row.runId());
        jdbc.update(
                """
                UPDATE run_attempts
                   SET status = CASE WHEN task_id = ? THEN 'FAILED' ELSE 'CANCELLED' END,
                       error_code = CASE WHEN task_id = ? THEN ? ELSE error_code END,
                       error_message = CASE WHEN task_id = ? THEN ? ELSE error_message END,
                       lease_expires_at = NULL, completed_at = ?, updated_at = ?
                 WHERE run_id = ? AND status IN ('CREATED','LEASED','RUNNING')
                """,
                row.taskId(),
                row.taskId(),
                reason,
                row.taskId(),
                message,
                now,
                now,
                row.runId());
        appendEvent(row, reason, now);
    }

    private void appendEvent(BudgetRow row, String reason, OffsetDateTime now) {
        jdbc.update(
                "UPDATE assistant_runs SET next_event_seq = next_event_seq + 1, updated_at = ? "
                        + "WHERE id = ?",
                now,
                row.runId());
        Long seq =
                jdbc.queryForObject(
                        "SELECT next_event_seq FROM assistant_runs WHERE id = ?",
                        Long.class,
                        row.runId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("message", message(reason));
        payload.put("taskId", row.taskId().toString());
        String payloadJson = JsonUtils.getJsonCodec().toJson(payload);
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO run_events
                    (id, org_id, user_id, run_id, task_id, seq, event_type, payload_json)
                SELECT ?, ?, r.user_id, ?, ?, ?, 'RUN_BUDGET_EXCEEDED', CAST(? AS JSON)
                  FROM assistant_runs r WHERE r.id = ?
                """,
                eventId,
                row.orgId(),
                row.runId(),
                row.taskId(),
                seq,
                payloadJson,
                row.runId());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", row.runId().toString());
        envelope.put("seq", seq);
        envelope.put("taskId", row.taskId().toString());
        envelope.put("payload", payload);
        jdbc.update(
                """
                INSERT INTO orchestration_outbox
                    (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
                VALUES (?, ?, ?, 'assistant_run', 'RUN_BUDGET_EXCEEDED', CAST(? AS JSON))
                """,
                UUID.randomUUID(),
                row.orgId(),
                row.runId(),
                JsonUtils.getJsonCodec().toJson(envelope));
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

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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

    private record BudgetRow(
            UUID runId,
            UUID orgId,
            String runStatus,
            Long runTokenBudget,
            long runConsumedTokens,
            Long runCostBudget,
            long runConsumedCost,
            Integer runCallBudget,
            int runConsumedCalls,
            OffsetDateTime runDeadline,
            UUID taskId,
            Long taskTokenBudget,
            long taskConsumedTokens,
            Long taskCostBudget,
            long taskConsumedCost,
            Integer taskCallBudget,
            int taskConsumedCalls,
            OffsetDateTime taskDeadline) {}

    private record BudgetScope(UUID orgId, UUID runId, UUID agentRunId) {}
}
