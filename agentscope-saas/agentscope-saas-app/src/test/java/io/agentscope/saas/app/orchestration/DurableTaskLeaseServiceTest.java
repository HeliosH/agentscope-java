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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.app.config.SaasProperties;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurableTaskLeaseServiceTest {

    private JdbcTemplate jdbc;
    private DurableTaskLeaseService leases;
    private UUID runId;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setSchedulerBatchSize(10);
        properties.getOrchestration().setSchedulerLeaseSeconds(60);
        properties.getOrchestration().setSchedulerRetryMaxSeconds(30);
        leases =
                new DurableTaskLeaseService(
                        jdbc,
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                        properties);
        runId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tier_policies (tier, max_sandboxes, monthly_token_quota) "
                        + "VALUES ('standard', 2, 100000)");
        jdbc.update("INSERT INTO users (id, role, tier) VALUES (?, 'member', 'standard')", userId);
        jdbc.update(
                "INSERT INTO assistant_runs "
                        + "(id, org_id, user_id, agent_id, session_id, status, next_event_seq, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, 'RUNNING', 0, ?)",
                runId,
                orgId,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now());
        insertTask(taskId, orgId, "IDEMPOTENT", 3);
    }

    @Test
    void onlyOneWorkerCanClaimReadyTask() {
        var first = leases.claimReady("worker-a", 1);
        var second = leases.claimReady("worker-b", 1);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).attemptNo()).isEqualTo(1);
        assertThat(second).isEmpty();
        assertThat(taskStatus(taskId)).isEqualTo("CLAIMED");
        assertThat(attemptStatus(first.get(0).attemptId())).isEqualTo("LEASED");
    }

    @Test
    void startsHeartbeatsAndCompletesLease() {
        var lease = leases.claimReady("worker-a", 1).get(0);

        assertThat(leases.start(lease.attemptId(), "worker-a")).isTrue();
        assertThat(leases.heartbeat(lease.attemptId(), "worker-a")).isTrue();
        assertThat(leases.heartbeat(lease.attemptId(), "worker-b")).isFalse();
        assertThat(leases.succeed(lease.attemptId(), "worker-a")).isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("SUCCEEDED");
        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        assertThat(eventTypes())
                .containsExactly(
                        "TASK_CLAIMED",
                        "TASK_STARTED",
                        "ATTEMPT_SUCCEEDED",
                        "TASK_SUCCEEDED",
                        "RUN_SUCCEEDED");
    }

    @Test
    void retryableFailureCreatesANewAttemptAfterBackoff() {
        var first = leases.claimReady("worker-a", 1).get(0);
        assertThat(leases.fail(first.attemptId(), "worker-a", "TEMPORARY", "try again")).isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("READY");
        assertThat(leases.claimReady("worker-b", 1)).isEmpty();
        jdbc.update(
                "UPDATE task_nodes SET next_attempt_at = ? WHERE id = ?",
                OffsetDateTime.now().minusSeconds(1),
                taskId);

        var second = leases.claimReady("worker-b", 1).get(0);
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(attemptStatus(first.attemptId())).isEqualTo("FAILED");
    }

    @Test
    void unsafeFailureRequiresManualActionInsteadOfRetry() {
        jdbc.update("UPDATE task_nodes SET retry_mode = 'MANUAL' WHERE id = ?", taskId);
        var lease = leases.claimReady("worker-a", 1).get(0);

        assertThat(leases.fail(lease.attemptId(), "worker-a", "SIDE_EFFECT", "unknown outcome"))
                .isTrue();

        assertThat(taskStatus(taskId)).isEqualTo("MANUAL_ACTION");
        assertThat(leases.claimReady("worker-b", 1)).isEmpty();
    }

    @Test
    void expiredAttemptIsAbandonedAndTaskBecomesRetryable() {
        var lease = leases.claimReady("crashed-worker", 1).get(0);
        jdbc.update(
                "UPDATE run_attempts SET lease_expires_at = ? WHERE id = ?",
                OffsetDateTime.now().minusSeconds(1),
                lease.attemptId());

        assertThat(leases.recoverExpired(10)).isEqualTo(1);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("ABANDONED");
        assertThat(taskStatus(taskId)).isEqualTo("READY");
    }

    private void insertTask(UUID id, UUID orgId, String retryMode, int maxAttempts) {
        jdbc.update(
                """
                INSERT INTO task_nodes
                    (id, org_id, run_id, title, input_json, status, priority, max_attempts,
                     retry_mode, retry_base_seconds, created_at, updated_at)
                VALUES (?, ?, ?, 'test task', '{}', 'READY', 0, ?, ?, 2, ?, ?)
                """,
                id,
                orgId,
                runId,
                maxAttempts,
                retryMode,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private String taskStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM task_nodes WHERE id = ?", String.class, id);
    }

    private String attemptStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM run_attempts WHERE id = ?", String.class, id);
    }

    private java.util.List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM run_events ORDER BY seq", String.class);
    }

    private void createSchema() {
        jdbc.execute(
                "CREATE TABLE tier_policies (tier VARCHAR(20) PRIMARY KEY, max_sandboxes INTEGER, "
                        + "monthly_token_quota BIGINT)");
        jdbc.execute(
                "CREATE TABLE users (id UUID PRIMARY KEY, role VARCHAR(20), tier VARCHAR(20))");
        jdbc.execute(
                """
                CREATE TABLE assistant_runs (
                    id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
                    agent_id UUID NOT NULL, session_id UUID NOT NULL,
                    status VARCHAR(32) NOT NULL, next_event_seq BIGINT NOT NULL,
                    failure_code VARCHAR(128), failure_message VARCHAR(2000),
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        jdbc.execute(
                """
                CREATE TABLE task_nodes (
                    id UUID PRIMARY KEY, org_id UUID NOT NULL, run_id UUID NOT NULL,
                    parent_id UUID, owner_agent_run_id UUID, sub_session_id VARCHAR(255),
                    title VARCHAR(500), input_json VARCHAR(4000), status VARCHAR(32) NOT NULL,
                    priority INTEGER NOT NULL, max_attempts INTEGER NOT NULL,
                    retry_mode VARCHAR(32) NOT NULL, retry_base_seconds INTEGER NOT NULL,
                    next_attempt_at TIMESTAMP WITH TIME ZONE, last_error_code VARCHAR(128),
                    last_error_message VARCHAR(2000), output_json JSON DEFAULT '{}',
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute(
                "CREATE TABLE task_edges (from_task_id UUID NOT NULL, to_task_id UUID NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE agent_runs (
                    id UUID PRIMARY KEY, run_id UUID NOT NULL, task_id UUID NOT NULL,
                    agent_type VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE,
                    completed_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute(
                """
                CREATE TABLE run_attempts (
                    id UUID PRIMARY KEY, org_id UUID NOT NULL, run_id UUID NOT NULL,
                    task_id UUID NOT NULL, agent_run_id UUID, attempt_no INTEGER NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    lease_owner VARCHAR(255), lease_expires_at TIMESTAMP WITH TIME ZONE,
                    heartbeat_at TIMESTAMP WITH TIME ZONE, idempotency_key VARCHAR(255),
                    error_code VARCHAR(128), error_message VARCHAR(2000),
                    started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute(
                """
                CREATE TABLE run_events (
                    id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
                    run_id UUID NOT NULL, task_id UUID, agent_run_id UUID, attempt_id UUID,
                    seq BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL, payload_json JSON,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute(
                """
                CREATE TABLE orchestration_outbox (
                    id UUID PRIMARY KEY, org_id UUID NOT NULL, aggregate_id UUID NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL,
                    payload_json JSON, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)
                """);
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:durable-task-"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
