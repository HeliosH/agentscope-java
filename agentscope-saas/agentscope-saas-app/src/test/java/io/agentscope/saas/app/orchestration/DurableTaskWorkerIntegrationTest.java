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

import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Executes a persisted READY task through the real worker and stub Harness model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class DurableTaskWorkerIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final TestDatabaseMapper database;

    @Autowired DurableTaskWorker worker;

    @Autowired
    DurableTaskWorkerIntegrationTest(@Qualifier("adminDataSource") DataSource adminDataSource) {
        this.database =
                MyBatisRepositoryTestSupport.mapper(adminDataSource, TestDatabaseMapper.class);
    }

    @Test
    void workerExecutesPersistedTaskAndCompletesRun() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        database.insertAgent(agentId, ORG_ID, USER_ID, "durable-worker-" + agentId);
        database.insertChatSession(
                sessionId, ORG_ID, USER_ID, agentId, "Durable worker integration");
        database.insertPlannedRun(runId, ORG_ID, USER_ID, agentId, sessionId, now);
        database.insertReadyTask(
                taskId,
                ORG_ID,
                runId,
                "Complete durable worker integration",
                "{\"prompt\":\"Complete durable worker integration\"}",
                now);

        assertThat(worker.pollOnce()).isEqualTo(1);
        awaitRun(runId, "SUCCEEDED", Duration.ofSeconds(15));

        assertThat(database.taskState(taskId).status()).isEqualTo("SUCCEEDED");
        assertThat(database.taskState(taskId).outputJson()).contains("durable worker integration");
        assertThat(database.runEventTypes(runId))
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
            if (expected.equals(database.runState(runId).status())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not reach " + expected + " within " + timeout);
    }
}
