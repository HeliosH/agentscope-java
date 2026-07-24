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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL-backed task lease state machine used by durable Agent workers. */
@Service
public class DurableTaskLeaseService {

    private static final String RETRY_IDEMPOTENT = "IDEMPOTENT";
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int MAX_COORDINATOR_ATTEMPTS = 33;
    private static final String COORDINATOR_CONTINUATION_INPUT =
            "{\"continuation\":true,\"prompt\":\"Review the completed subagent results and "
                    + "continue the original task. Produce the final answer, or delegate only "
                    + "when additional work is required.\"}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SaasProperties properties;

    @Autowired
    public DurableTaskLeaseService(
            @Qualifier("adminDataSource") DataSource adminDataSource, SaasProperties properties) {
        this(
                new JdbcTemplate(adminDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(adminDataSource)),
                properties);
    }

    DurableTaskLeaseService(
            JdbcTemplate jdbc, TransactionTemplate transactions, SaasProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Claims dependency-ready nodes. A conditional status update prevents duplicate claims. */
    public List<TaskLease> claimReady(String workerId, int requestedLimit) {
        requireWorker(workerId);
        int limit =
                Math.max(
                        1,
                        Math.min(
                                requestedLimit,
                                Math.max(
                                        1, properties.getOrchestration().getSchedulerBatchSize())));
        OffsetDateTime now = OffsetDateTime.now();
        List<TaskCandidate> candidates = loadReadyCandidates(now, limit * 2);
        List<TaskLease> leases = new ArrayList<>();
        for (TaskCandidate candidate : candidates) {
            if (leases.size() >= limit) {
                break;
            }
            TaskLease lease =
                    transactions.execute(status -> claimCandidate(candidate, workerId, now));
            if (lease != null) {
                leases.add(lease);
            }
        }
        return leases;
    }

    /** Moves a leased Attempt into RUNNING and retains the same lease owner. */
    public boolean start(UUID attemptId, String workerId) {
        requireWorker(workerId);
        Boolean started =
                transactions.execute(
                        status -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            AttemptRef ref = findAttempt(attemptId, workerId);
                            if (ref == null) {
                                return false;
                            }
                            int updated =
                                    jdbc.update(
                                            """
                                            UPDATE run_attempts
                                               SET status = 'RUNNING', started_at = COALESCE(started_at, ?),
                                                   heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
                                             WHERE id = ? AND lease_owner = ? AND status = 'LEASED'
                                               AND lease_expires_at >= ?
                                            """,
                                            now,
                                            now,
                                            leaseExpiry(now),
                                            now,
                                            attemptId,
                                            workerId,
                                            now);
                            if (updated != 1) {
                                return false;
                            }
                            jdbc.update(
                                    "UPDATE task_nodes SET status = 'RUNNING', updated_at = ? "
                                            + "WHERE id = ? AND status = 'CLAIMED'",
                                    now,
                                    ref.taskId());
                            if (ref.agentRunId() != null) {
                                jdbc.update(
                                        "UPDATE agent_runs SET status = 'RUNNING', updated_at = ? "
                                                + "WHERE id = ? AND status IN ('READY', 'CLAIMED')",
                                        now,
                                        ref.agentRunId());
                            }
                            appendEvent(ref, "TASK_STARTED", payload(attemptId, workerId, null));
                            return true;
                        });
        return Boolean.TRUE.equals(started);
    }

    /** Extends a live Attempt lease. False means ownership was lost or the lease expired. */
    public boolean heartbeat(UUID attemptId, String workerId) {
        requireWorker(workerId);
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.update(
                        """
                        UPDATE run_attempts
                           SET heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
                         WHERE id = ? AND lease_owner = ? AND status IN ('LEASED', 'RUNNING')
                           AND lease_expires_at >= ?
                        """,
                        now,
                        leaseExpiry(now),
                        now,
                        attemptId,
                        workerId,
                        now)
                == 1;
    }

    public boolean succeed(UUID attemptId, String workerId) {
        return succeed(attemptId, workerId, "{}");
    }

    public boolean succeed(UUID attemptId, String workerId, String outputJson) {
        return finish(attemptId, workerId, "SUCCEEDED", null, null, outputJson, false);
    }

    public boolean fail(UUID attemptId, String workerId, String errorCode, String errorMessage) {
        return finish(attemptId, workerId, "FAILED", errorCode, errorMessage, null, false);
    }

    /** Reclaims Attempts whose worker stopped heartbeating. Retries always use a new Attempt row. */
    public int recoverExpired(int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        OffsetDateTime now = OffsetDateTime.now();
        List<ExpiredAttempt> expired =
                jdbc.query(
                        """
                        SELECT id, lease_owner
                          FROM run_attempts
                         WHERE status IN ('LEASED', 'RUNNING')
                           AND lease_expires_at < ?
                         ORDER BY lease_expires_at ASC
                         LIMIT ?
                        """,
                        (rs, rowNum) ->
                                new ExpiredAttempt(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("lease_owner")),
                        now,
                        limit);
        int recovered = 0;
        for (ExpiredAttempt attempt : expired) {
            if (finish(
                    attempt.id(),
                    attempt.workerId(),
                    "ABANDONED",
                    "WORKER_LEASE_EXPIRED",
                    "Worker heartbeat lease expired",
                    null,
                    true)) {
                recovered++;
            }
        }
        return recovered;
    }

    private List<TaskCandidate> loadReadyCandidates(OffsetDateTime now, int limit) {
        return jdbc.query(
                """
                SELECT t.id, t.org_id, t.run_id, r.user_id, r.agent_id, r.session_id,
                       ar.id AS agent_run_id, COALESCE(ar.agent_type, 'assistant') AS agent_type,
                       t.sub_session_id,
                       u.role, u.tier, COALESCE(p.max_sandboxes, 1) AS max_sandboxes,
                       COALESCE(p.monthly_token_quota, 0) AS token_quota,
                       t.title, t.input_json, t.max_attempts,
                       t.retry_mode, t.retry_base_seconds,
                       COALESCE((SELECT MAX(a.attempt_no) FROM run_attempts a
                                  WHERE a.task_id = t.id), 0) AS last_attempt_no
                  FROM task_nodes t
                  JOIN assistant_runs r ON r.id = t.run_id
                  LEFT JOIN agent_runs ar ON ar.id = t.owner_agent_run_id
                  JOIN users u ON u.id = r.user_id
                  LEFT JOIN tier_policies p ON p.tier = u.tier
                 WHERE t.status = 'READY'
                   AND r.status = 'RUNNING'
                   AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM task_edges e
                       JOIN task_nodes dependency ON dependency.id = e.from_task_id
                        WHERE e.to_task_id = t.id AND dependency.status <> 'SUCCEEDED'
                   )
                 ORDER BY t.priority DESC, t.created_at ASC
                 LIMIT ?
                """,
                this::mapCandidate,
                now,
                limit);
    }

    private TaskCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new TaskCandidate(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("agent_run_id", UUID.class),
                rs.getString("agent_type"),
                rs.getString("sub_session_id"),
                rs.getString("role"),
                rs.getString("tier"),
                rs.getInt("max_sandboxes"),
                rs.getLong("token_quota"),
                rs.getString("title"),
                rs.getString("input_json"),
                rs.getInt("max_attempts"),
                rs.getString("retry_mode"),
                rs.getInt("retry_base_seconds"),
                rs.getInt("last_attempt_no"));
    }

    private TaskLease claimCandidate(TaskCandidate candidate, String workerId, OffsetDateTime now) {
        int attemptNo = candidate.lastAttemptNo() + 1;
        if (attemptNo > candidate.maxAttempts()) {
            return null;
        }
        int claimed =
                jdbc.update(
                        """
                        UPDATE task_nodes SET status = 'CLAIMED', next_attempt_at = NULL,
                                              updated_at = ?
                         WHERE id = ? AND status = 'READY'
                           AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                        """,
                        now,
                        candidate.taskId(),
                        now);
        if (claimed != 1) {
            return null;
        }
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime expiresAt = leaseExpiry(now);
        jdbc.update(
                """
                INSERT INTO run_attempts
                    (id, org_id, run_id, task_id, agent_run_id, attempt_no, status, lease_owner,
                     lease_expires_at, heartbeat_at, idempotency_key, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'LEASED', ?, ?, ?, ?, ?)
                """,
                attemptId,
                candidate.orgId(),
                candidate.runId(),
                candidate.taskId(),
                candidate.agentRunId(),
                attemptNo,
                workerId,
                expiresAt,
                now,
                "task:" + candidate.taskId() + ":attempt:" + attemptNo,
                now);
        AttemptRef ref =
                new AttemptRef(
                        attemptId,
                        candidate.orgId(),
                        candidate.runId(),
                        candidate.taskId(),
                        candidate.agentRunId(),
                        attemptNo,
                        candidate.maxAttempts(),
                        candidate.retryMode(),
                        candidate.retryBaseSeconds());
        appendEvent(ref, "TASK_CLAIMED", payload(attemptId, workerId, attemptNo));
        return new TaskLease(
                attemptId,
                candidate.orgId(),
                candidate.runId(),
                candidate.userId(),
                candidate.agentId(),
                candidate.sessionId(),
                candidate.agentRunId(),
                candidate.agentType(),
                candidate.subSessionId(),
                candidate.role(),
                candidate.tier(),
                candidate.maxSandboxes(),
                candidate.tokenQuota(),
                candidate.taskId(),
                attemptNo,
                workerId,
                expiresAt,
                candidate.title(),
                candidate.inputJson());
    }

    private boolean finish(
            UUID attemptId,
            String workerId,
            String attemptStatus,
            String errorCode,
            String errorMessage,
            String outputJson,
            boolean requireExpired) {
        requireWorker(workerId);
        Boolean finished =
                transactions.execute(
                        status -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            AttemptRef ref = findAttempt(attemptId, workerId);
                            if (ref == null) {
                                return false;
                            }
                            String expiryCondition =
                                    requireExpired ? " AND lease_expires_at < ?" : "";
                            List<Object> args = new ArrayList<>();
                            args.add(attemptStatus);
                            args.add(truncate(errorCode, 128));
                            args.add(truncate(errorMessage, MAX_ERROR_LENGTH));
                            args.add(now);
                            args.add(now);
                            args.add(attemptId);
                            args.add(workerId);
                            if (requireExpired) {
                                args.add(now);
                            }
                            int updated =
                                    jdbc.update(
                                            "UPDATE run_attempts SET status = ?, error_code = ?, "
                                                    + "error_message = ?, completed_at = ?, "
                                                    + "lease_expires_at = NULL, updated_at = ? "
                                                    + "WHERE id = ? AND lease_owner = ? AND status "
                                                    + "IN ('LEASED', 'RUNNING')"
                                                    + expiryCondition,
                                            args.toArray());
                            if (updated != 1) {
                                return false;
                            }
                            appendEvent(
                                    ref,
                                    "ATTEMPT_" + attemptStatus,
                                    payload(attemptId, workerId, ref.attemptNo()));
                            if ("SUCCEEDED".equals(attemptStatus)) {
                                completeTask(ref, now, normalizeJson(outputJson));
                            } else {
                                retryOrStopTask(ref, now, errorCode, errorMessage);
                            }
                            return true;
                        });
        return Boolean.TRUE.equals(finished);
    }

    private void completeTask(AttemptRef ref, OffsetDateTime now, String outputJson) {
        boolean coordinator = isCoordinatorTask(ref.taskId());
        jdbc.update(
                """
                UPDATE task_nodes SET status = 'SUCCEEDED', completed_at = ?, updated_at = ?,
                                      output_json = CAST(? AS JSON), last_error_code = NULL,
                                      last_error_message = NULL
                 WHERE id = ? AND status IN ('CLAIMED', 'RUNNING')
                """,
                now,
                now,
                outputJson,
                ref.taskId());
        updateAgentRun(ref.agentRunId(), "SUCCEEDED", now);
        appendEvent(ref, "TASK_SUCCEEDED", "{}");
        jdbc.update(
                """
                UPDATE task_nodes target SET status = 'READY', updated_at = ?
                 WHERE target.run_id = ? AND target.status = 'PENDING'
                   AND NOT EXISTS (
                       SELECT 1 FROM task_edges edge
                       JOIN task_nodes dependency ON dependency.id = edge.from_task_id
                        WHERE edge.to_task_id = target.id AND dependency.status <> 'SUCCEEDED'
                   )
                """,
                now,
                ref.runId());
        if (!coordinator) {
            scheduleCoordinatorContinuation(ref, now);
            return;
        }
        int runCompleted =
                jdbc.update(
                        """
                        UPDATE assistant_runs SET status = 'SUCCEEDED', completed_at = ?,
                                                  updated_at = ?
                         WHERE id = ? AND status = 'RUNNING'
                           AND NOT EXISTS (
                               SELECT 1 FROM task_nodes task
                                WHERE task.run_id = ?
                                  AND task.status NOT IN ('SUCCEEDED', 'CANCELLED')
                           )
                        """,
                        now,
                        now,
                        ref.runId(),
                        ref.runId());
        if (runCompleted == 1) {
            appendEvent(ref, "RUN_SUCCEEDED", "{}");
        } else {
            appendEvent(ref, "COORDINATOR_SUCCEEDED", "{}");
        }
    }

    private boolean isCoordinatorTask(UUID taskId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM task_nodes WHERE id = ? AND parent_id IS NULL",
                        Integer.class,
                        taskId);
        return count != null && count == 1;
    }

    private void scheduleCoordinatorContinuation(AttemptRef ref, OffsetDateTime now) {
        List<CoordinatorRef> coordinators =
                jdbc.query(
                        """
                        SELECT root.id, root.owner_agent_run_id
                          FROM task_nodes root
                         WHERE root.run_id = ? AND root.parent_id IS NULL
                           AND root.status = 'SUCCEEDED'
                           AND EXISTS (
                               SELECT 1 FROM task_nodes child
                                WHERE child.run_id = root.run_id AND child.parent_id IS NOT NULL
                           )
                           AND NOT EXISTS (
                               SELECT 1 FROM task_nodes child
                                WHERE child.run_id = root.run_id
                                  AND child.parent_id IS NOT NULL
                                  AND child.status NOT IN ('SUCCEEDED', 'CANCELLED')
                           )
                        """,
                        (rs, rowNum) ->
                                new CoordinatorRef(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("owner_agent_run_id", UUID.class)),
                        ref.runId());
        if (coordinators.isEmpty()) {
            return;
        }
        CoordinatorRef coordinator = coordinators.get(0);
        int scheduled =
                jdbc.update(
                        """
                        UPDATE task_nodes
                           SET status = 'READY', input_json = CAST(? AS JSON),
                               max_attempts = CASE WHEN max_attempts < ? THEN ? ELSE max_attempts END,
                               next_attempt_at = NULL, completed_at = NULL, updated_at = ?
                         WHERE id = ? AND status = 'SUCCEEDED'
                        """,
                        COORDINATOR_CONTINUATION_INPUT,
                        MAX_COORDINATOR_ATTEMPTS,
                        MAX_COORDINATOR_ATTEMPTS,
                        now,
                        coordinator.taskId());
        if (scheduled != 1) {
            return;
        }
        if (coordinator.agentRunId() != null) {
            jdbc.update(
                    """
                    UPDATE agent_runs
                       SET status = 'READY', completed_at = NULL, updated_at = ?
                     WHERE id = ?
                    """,
                    now,
                    coordinator.agentRunId());
        }
        appendEvent(
                ref,
                coordinator.taskId(),
                coordinator.agentRunId(),
                null,
                "COORDINATOR_CONTINUATION_READY",
                "{}");
    }

    private void retryOrStopTask(
            AttemptRef ref, OffsetDateTime now, String errorCode, String errorMessage) {
        boolean retryable = RETRY_IDEMPOTENT.equals(ref.retryMode());
        boolean hasAttempts = ref.attemptNo() < ref.maxAttempts();
        if (retryable && hasAttempts) {
            OffsetDateTime next = now.plusSeconds(retryDelay(ref));
            jdbc.update(
                    """
                    UPDATE task_nodes SET status = 'READY', next_attempt_at = ?, updated_at = ?,
                                          last_error_code = ?, last_error_message = ?
                     WHERE id = ? AND status IN ('CLAIMED', 'RUNNING')
                    """,
                    next,
                    now,
                    truncate(errorCode, 128),
                    truncate(errorMessage, MAX_ERROR_LENGTH),
                    ref.taskId());
            updateAgentRun(ref.agentRunId(), "READY", now);
            appendEvent(ref, "TASK_RETRY_SCHEDULED", payload(null, null, ref.attemptNo() + 1));
            return;
        }
        String taskStatus = retryable ? "FAILED" : "MANUAL_ACTION";
        jdbc.update(
                """
                UPDATE task_nodes SET status = ?, completed_at = ?, updated_at = ?,
                                      last_error_code = ?, last_error_message = ?
                 WHERE id = ? AND status IN ('CLAIMED', 'RUNNING')
                """,
                taskStatus,
                now,
                now,
                truncate(errorCode, 128),
                truncate(errorMessage, MAX_ERROR_LENGTH),
                ref.taskId());
        updateAgentRun(ref.agentRunId(), taskStatus, now);
        appendEvent(ref, "TASK_" + taskStatus, "{}");
        if ("FAILED".equals(taskStatus)) {
            int runFailed =
                    jdbc.update(
                            """
                            UPDATE assistant_runs SET status = 'FAILED', failure_code = ?,
                                                      failure_message = ?, completed_at = ?,
                                                      updated_at = ?
                             WHERE id = ? AND status = 'RUNNING'
                            """,
                            truncate(errorCode, 128),
                            truncate(errorMessage, MAX_ERROR_LENGTH),
                            now,
                            now,
                            ref.runId());
            if (runFailed == 1) {
                jdbc.update(
                        "UPDATE task_nodes SET status = 'CANCELLED', completed_at = ?, updated_at ="
                                + " ? WHERE run_id = ? AND id <> ? AND status IN "
                                + "('PENDING','READY','CLAIMED','RUNNING')",
                        now,
                        now,
                        ref.runId(),
                        ref.taskId());
                jdbc.update(
                        "UPDATE run_attempts SET status = 'CANCELLED', completed_at = ?, "
                                + "lease_expires_at = NULL, updated_at = ? WHERE run_id = ? "
                                + "AND task_id <> ? AND status IN ('CREATED','LEASED','RUNNING')",
                        now,
                        now,
                        ref.runId(),
                        ref.taskId());
                jdbc.update(
                        "UPDATE agent_runs SET status = 'CANCELLED', completed_at = ?, updated_at ="
                                + " ? WHERE run_id = ? AND task_id <> ? AND status NOT IN "
                                + "('SUCCEEDED','FAILED','CANCELLED')",
                        now,
                        now,
                        ref.runId(),
                        ref.taskId());
                appendEvent(ref, "RUN_FAILED", "{}");
            }
        }
    }

    private AttemptRef findAttempt(UUID attemptId, String workerId) {
        List<AttemptRef> rows =
                jdbc.query(
                        """
                        SELECT a.id, a.org_id, a.run_id, a.task_id, a.agent_run_id, a.attempt_no,
                               t.max_attempts, t.retry_mode, t.retry_base_seconds
                          FROM run_attempts a JOIN task_nodes t ON t.id = a.task_id
                         WHERE a.id = ? AND a.lease_owner = ?
                        """,
                        (rs, rowNum) ->
                                new AttemptRef(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("org_id", UUID.class),
                                        rs.getObject("run_id", UUID.class),
                                        rs.getObject("task_id", UUID.class),
                                        rs.getObject("agent_run_id", UUID.class),
                                        rs.getInt("attempt_no"),
                                        rs.getInt("max_attempts"),
                                        rs.getString("retry_mode"),
                                        rs.getInt("retry_base_seconds")),
                        attemptId,
                        workerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void appendEvent(AttemptRef ref, String eventType, String payloadJson) {
        appendEvent(ref, ref.taskId(), ref.agentRunId(), ref.attemptId(), eventType, payloadJson);
    }

    private void appendEvent(
            AttemptRef ref,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String eventType,
            String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated =
                jdbc.update(
                        "UPDATE assistant_runs SET next_event_seq = next_event_seq + 1, "
                                + "updated_at = ? WHERE id = ?",
                        now,
                        ref.runId());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Run disappeared while appending event: " + ref.runId());
        }
        Long seq =
                jdbc.queryForObject(
                        "SELECT next_event_seq FROM assistant_runs WHERE id = ?",
                        Long.class,
                        ref.runId());
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO run_events
                    (id, org_id, user_id, run_id, task_id, agent_run_id, attempt_id, seq, event_type,
                     payload_json)
                SELECT ?, ?, r.user_id, ?, ?, ?, ?, ?, ?, CAST(? AS JSON)
                  FROM assistant_runs r WHERE r.id = ?
                """,
                eventId,
                ref.orgId(),
                ref.runId(),
                taskId,
                agentRunId,
                attemptId,
                seq,
                eventType,
                payloadJson,
                ref.runId());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", ref.runId().toString());
        envelope.put("seq", seq);
        envelope.put("taskId", taskId != null ? taskId.toString() : null);
        envelope.put("payload", JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class));
        jdbc.update(
                """
                INSERT INTO orchestration_outbox
                    (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
                VALUES (?, ?, ?, 'assistant_run', ?, CAST(? AS JSON))
                """,
                UUID.randomUUID(),
                ref.orgId(),
                ref.runId(),
                eventType,
                JsonUtils.getJsonCodec().toJson(envelope));
    }

    private long retryDelay(AttemptRef ref) {
        long maximum = Math.max(1L, properties.getOrchestration().getSchedulerRetryMaxSeconds());
        long delay = Math.max(1L, ref.retryBaseSeconds());
        for (int i = 1; i < ref.attemptNo() && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return delay;
    }

    private void updateAgentRun(UUID agentRunId, String status, OffsetDateTime now) {
        if (agentRunId == null) {
            return;
        }
        boolean terminal =
                "SUCCEEDED".equals(status)
                        || "FAILED".equals(status)
                        || "MANUAL_ACTION".equals(status)
                        || "CANCELLED".equals(status);
        jdbc.update(
                "UPDATE agent_runs SET status = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                status,
                now,
                terminal ? now : null,
                agentRunId);
    }

    private OffsetDateTime leaseExpiry(OffsetDateTime now) {
        return now.plusSeconds(
                Math.max(1L, properties.getOrchestration().getSchedulerLeaseSeconds()));
    }

    private static String payload(UUID attemptId, String workerId, Integer attemptNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attemptId != null ? attemptId.toString() : null);
        payload.put("workerId", workerId);
        payload.put("attemptNo", attemptNo);
        return JsonUtils.getJsonCodec().toJson(payload);
    }

    private static void requireWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalArgumentException("workerId must contain 1-255 characters");
        }
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    private static String normalizeJson(String value) {
        String normalized = value == null || value.isBlank() ? "{}" : value;
        JsonUtils.getJsonCodec().fromJson(normalized, Object.class);
        return normalized;
    }

    private record TaskCandidate(
            UUID taskId,
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID agentRunId,
            String agentType,
            String subSessionId,
            String role,
            String tier,
            int maxSandboxes,
            long tokenQuota,
            String title,
            String inputJson,
            int maxAttempts,
            String retryMode,
            int retryBaseSeconds,
            int lastAttemptNo) {}

    private record AttemptRef(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            int maxAttempts,
            String retryMode,
            int retryBaseSeconds) {}

    private record ExpiredAttempt(UUID id, String workerId) {}

    private record CoordinatorRef(UUID taskId, UUID agentRunId) {}

    public record TaskLease(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID agentRunId,
            String agentType,
            String subSessionId,
            String role,
            String tier,
            int maxSandboxes,
            long tokenQuota,
            UUID taskId,
            int attemptNo,
            String workerId,
            OffsetDateTime leaseExpiresAt,
            String title,
            String inputJson) {}
}
