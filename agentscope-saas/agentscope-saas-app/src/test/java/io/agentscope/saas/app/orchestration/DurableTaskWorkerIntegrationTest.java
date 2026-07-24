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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Executes a persisted READY task through the real worker and stub Harness model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class DurableTaskWorkerIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final JdbcTemplate jdbc;

    @Autowired DurableTaskWorker worker;

    @Autowired
    DurableTaskWorkerIntegrationTest(@Qualifier("adminDataSource") DataSource adminDataSource) {
        this.jdbc = new JdbcTemplate(adminDataSource);
    }

    @Test
    void workerExecutesPersistedTaskAndCompletesRun() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO agents (id, org_id, user_id, name, status) VALUES (?, ?, ?, ?, ?)",
                agentId,
                ORG_ID,
                USER_ID,
                "durable-worker-" + agentId,
                "active");
        jdbc.update(
                "INSERT INTO chat_sessions (id, org_id, user_id, agent_id, title) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId,
                ORG_ID,
                USER_ID,
                agentId,
                "Durable worker integration");
        jdbc.update(
                """
                INSERT INTO assistant_runs
                    (id, org_id, user_id, agent_id, session_id, mode, status,
                     cancel_requested, next_event_seq, started_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PLANNED', 'RUNNING', FALSE, 0, ?, ?)
                """,
                runId,
                ORG_ID,
                USER_ID,
                agentId,
                sessionId,
                now,
                now);
        jdbc.update(
                """
                INSERT INTO task_nodes
                    (id, org_id, run_id, title, task_type, status, priority, input_json,
                     expected_output_json, acceptance_json, workspace_mode, max_attempts,
                     retry_mode, retry_base_seconds, updated_at)
                VALUES (?, ?, ?, ?, 'agent', 'READY', 0, CAST(? AS JSON),
                        CAST('{}' AS JSON), CAST('[]' AS JSON), 'NONE', 2,
                        'IDEMPOTENT', 1, ?)
                """,
                taskId,
                ORG_ID,
                runId,
                "Complete durable worker integration",
                "{\"prompt\":\"Complete durable worker integration\"}",
                now);

        assertThat(worker.pollOnce()).isEqualTo(1);
        awaitRun(runId, "SUCCEEDED", Duration.ofSeconds(15));

        assertThat(value("SELECT status FROM task_nodes WHERE id = ?", taskId))
                .isEqualTo("SUCCEEDED");
        assertThat(value("SELECT output_json FROM task_nodes WHERE id = ?", taskId))
                .contains("durable worker integration");
        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM run_events WHERE run_id = ? ORDER BY seq",
                                String.class,
                                runId))
                .containsExactly(
                        "TASK_CLAIMED",
                        "TASK_STARTED",
                        "ATTEMPT_SUCCEEDED",
                        "TASK_SUCCEEDED",
                        "RUN_SUCCEEDED");
    }

    private void awaitRun(UUID runId, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(value("SELECT status FROM assistant_runs WHERE id = ?", runId))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not reach " + expected + " within " + timeout);
    }

    private String value(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }
}
