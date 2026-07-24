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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Verifies durable Harness task submission, execution state, and parent-result delivery. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "saas.subagents.execution-mode=durable",
            "saas.orchestration.scheduler-enabled=true",
            "saas.orchestration.scheduler-poll-millis=3600000"
        })
@ActiveProfiles("local")
class PgTaskRepositoryIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final JdbcTemplate jdbc;

    @Autowired PgTaskRepository tasks;
    @Autowired RunOrchestrationService runs;
    @Autowired DurableTaskLeaseService leases;
    @Autowired DurableTaskWorker worker;

    @Autowired
    PgTaskRepositoryIntegrationTest(@Qualifier("adminDataSource") DataSource adminDataSource) {
        this.jdbc = new JdbcTemplate(adminDataSource);
    }

    @BeforeEach
    void bindTenant() {
        TenantContextHolder.setOrgId(ORG_ID.toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void durableChildOutlivesCoordinatorAndIsDeliveredExactlyOnce() {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        seedAgentAndSession(agentId, sessionId);
        TenantContext tenant = tenant();
        var run =
                runs.createDirectRun(tenant, agentId, sessionId, null, "Delegate durable research");
        RuntimeContext context = context(tenant, agentId, sessionId, run.runId());

        var submitted =
                tasks.putTask(
                        context,
                        "task_research",
                        "researcher",
                        sessionId.toString(),
                        new TaskRunSpec.DurableLocalTaskRunSpec(
                                "sub-research", "Research durable execution", () -> "not-used"));
        assertThat(submitted.getTaskStatus()).isEqualTo(TaskStatus.RUNNING);

        runs.markSucceeded(tenant, agentId, run.runId());
        assertThat(runStatus(run.runId())).isEqualTo("RUNNING");

        var lease = leases.claimReady("worker-pg-task", 1).get(0);
        assertThat(lease.agentType()).isEqualTo("researcher");
        assertThat(lease.subSessionId()).isEqualTo("sub-research");
        assertThat(lease.agentRunId()).isNotNull();
        assertThat(leases.start(lease.attemptId(), "worker-pg-task")).isTrue();
        assertThat(
                        leases.succeed(
                                lease.attemptId(),
                                "worker-pg-task",
                                "{\"status\":\"succeeded\",\"summary\":\"research done\"}"))
                .isTrue();

        assertThat(runStatus(run.runId())).isEqualTo("RUNNING");
        completeCoordinatorContinuation(run.runId(), "worker-pg-coordinator");
        assertThat(runStatus(run.runId())).isEqualTo("SUCCEEDED");
        assertThat(tasks.getTask(context, sessionId.toString(), "task_research").getResult())
                .isEqualTo("research done");
        assertThat(tasks.findPendingDeliveries(context, sessionId.toString()))
                .singleElement()
                .satisfies(
                        delivery -> {
                            assertThat(delivery.taskId()).isEqualTo("task_research");
                            assertThat(delivery.agentId()).isEqualTo("researcher");
                            assertThat(delivery.result()).isEqualTo("research done");
                        });

        tasks.markDelivered(context, sessionId.toString(), "task_research");
        assertThat(tasks.findPendingDeliveries(context, sessionId.toString())).isEmpty();
        assertThat(tasks.isDelivered(context, sessionId.toString(), "task_research")).isTrue();
    }

    @Test
    void cancellingLastChildSettlesRunAndInvalidatesTask() {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        seedAgentAndSession(agentId, sessionId);
        TenantContext tenant = tenant();
        var run = runs.createDirectRun(tenant, agentId, sessionId, null, "Cancel child task");
        RuntimeContext context = context(tenant, agentId, sessionId, run.runId());
        tasks.putTask(
                context,
                "task_cancel",
                "researcher",
                sessionId.toString(),
                new TaskRunSpec.DurableLocalTaskRunSpec(
                        "sub-cancel", "No longer needed", () -> "not-used"));
        runs.markSucceeded(tenant, agentId, run.runId());

        assertThat(tasks.cancelTask(context, sessionId.toString(), "task_cancel")).isTrue();

        assertThat(runStatus(run.runId())).isEqualTo("RUNNING");
        completeCoordinatorContinuation(run.runId(), "worker-pg-cancel-coordinator");
        assertThat(runStatus(run.runId())).isEqualTo("SUCCEEDED");
        assertThat(tasks.getTask(context, sessionId.toString(), "task_cancel").getTaskStatus())
                .isEqualTo(TaskStatus.CANCELLED);
        assertThat(tasks.findPendingDeliveries(context, sessionId.toString()))
                .singleElement()
                .satisfies(
                        delivery -> assertThat(delivery.status()).isEqualTo(TaskStatus.CANCELLED));
    }

    @Test
    void workerAutomaticallyResumesCoordinatorAndPersistsFinalReply() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        seedAgentAndSession(agentId, sessionId);
        TenantContext tenant = tenant();
        var run = runs.createDirectRun(tenant, agentId, sessionId, null, "Automatic continuation");
        RuntimeContext context = context(tenant, agentId, sessionId, run.runId());
        tasks.putTask(
                context,
                "task_auto",
                "researcher",
                sessionId.toString(),
                new TaskRunSpec.DurableLocalTaskRunSpec(
                        "sub-auto", "Research automatically", () -> "not-used"));
        runs.markSucceeded(tenant, agentId, run.runId());

        var child = leases.claimReady("worker-auto-child", 1).get(0);
        assertThat(leases.start(child.attemptId(), "worker-auto-child")).isTrue();
        assertThat(
                        leases.succeed(
                                child.attemptId(),
                                "worker-auto-child",
                                "{\"status\":\"succeeded\",\"summary\":\"automatic research\"}"))
                .isTrue();
        assertThat(tasks.findPendingDeliveries(context, sessionId.toString())).hasSize(1);

        assertThat(worker.pollOnce()).isEqualTo(1);
        awaitRun(run.runId(), "SUCCEEDED", Duration.ofSeconds(15));

        assertThat(tasks.isDelivered(context, sessionId.toString(), "task_auto")).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM chat_messages WHERE source_run_id = ?",
                                Integer.class,
                                run.runId()))
                .isEqualTo(1);
    }

    @Test
    void nestedSubagentsPreserveParentageAndEnforceDepthLimit() {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        seedAgentAndSession(agentId, sessionId);
        TenantContext tenant = tenant();
        var run = runs.createDirectRun(tenant, agentId, sessionId, null, "Nested delegation");
        RuntimeContext parent = context(tenant, agentId, sessionId, run.runId());
        try {
            UUID depthOne = submitNested(parent, sessionId, "depth-1", "sub-depth-1");
            UUID depthTwo =
                    submitNested(
                            context(tenant, agentId, sessionId, run.runId(), depthOne),
                            sessionId,
                            "depth-2",
                            "sub-depth-2");
            UUID depthThree =
                    submitNested(
                            context(tenant, agentId, sessionId, run.runId(), depthTwo),
                            sessionId,
                            "depth-3",
                            "sub-depth-3");

            assertThat(agentRunDepth(depthOne)).isEqualTo(1);
            assertThat(agentRunDepth(depthTwo)).isEqualTo(2);
            assertThat(agentRunDepth(depthThree)).isEqualTo(3);
            assertThat(agentRunParent(depthTwo)).isEqualTo(depthOne);
            assertThat(agentRunParent(depthThree)).isEqualTo(depthTwo);
            assertThatThrownBy(
                            () ->
                                    submitNested(
                                            context(
                                                    tenant,
                                                    agentId,
                                                    sessionId,
                                                    run.runId(),
                                                    depthThree),
                                            sessionId,
                                            "depth-4",
                                            "sub-depth-4"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("maximum depth exceeded");
        } finally {
            runs.cancel(tenant, agentId, run.runId());
        }
    }

    @Test
    void concurrentWorkersClaimReadyTaskOnlyOnce() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        seedAgentAndSession(agentId, sessionId);
        TenantContext tenant = tenant();
        var run = runs.createDirectRun(tenant, agentId, sessionId, null, "Concurrent claim");
        RuntimeContext context = context(tenant, agentId, sessionId, run.runId());
        tasks.putTask(
                context,
                "task_concurrent",
                "researcher",
                sessionId.toString(),
                new TaskRunSpec.DurableLocalTaskRunSpec(
                        "sub-concurrent", "Claim exactly once", () -> "not-used"));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<DurableTaskLeaseService.TaskLease>> first =
                    pool.submit(() -> claimAfterBarrier("worker-concurrent-1", ready, start));
            Future<List<DurableTaskLeaseService.TaskLease>> second =
                    pool.submit(() -> claimAfterBarrier("worker-concurrent-2", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<DurableTaskLeaseService.TaskLease> claimed =
                    java.util.stream.Stream.concat(
                                    first.get(10, TimeUnit.SECONDS).stream(),
                                    second.get(10, TimeUnit.SECONDS).stream())
                            .filter(lease -> run.runId().equals(lease.runId()))
                            .toList();

            assertThat(claimed).singleElement();
        } finally {
            pool.shutdownNow();
            runs.cancel(tenant, agentId, run.runId());
        }
    }

    private static TenantContext tenant() {
        return new TenantContext(
                ORG_ID.toString(), USER_ID.toString(), "member", "standard", 2, 100_000);
    }

    private static RuntimeContext context(
            TenantContext tenant, UUID agentId, UUID sessionId, UUID runId) {
        return context(tenant, agentId, sessionId, runId, null);
    }

    private static RuntimeContext context(
            TenantContext tenant, UUID agentId, UUID sessionId, UUID runId, UUID agentRunId) {
        return RuntimeContext.builder()
                .userId(USER_ID.toString())
                .sessionId(sessionId.toString())
                .put(RunOrchestrationService.ATTR_RUN_ID, runId.toString())
                .put(
                        RunOrchestrationService.ATTR_AGENT_RUN_ID,
                        agentRunId != null ? agentRunId.toString() : null)
                .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agentId.toString())
                .put(TenantContext.class, tenant)
                .put(TenantContext.ATTR_KEY, tenant)
                .build();
    }

    private UUID submitNested(
            RuntimeContext context, UUID sessionId, String taskId, String subSessionId) {
        tasks.putTask(
                context,
                taskId,
                "researcher",
                sessionId.toString(),
                new TaskRunSpec.DurableLocalTaskRunSpec(
                        subSessionId, "Nested task " + taskId, () -> "not-used"));
        return jdbc.queryForObject(
                "SELECT owner_agent_run_id FROM task_nodes WHERE external_task_id = ?",
                UUID.class,
                taskId);
    }

    private void completeCoordinatorContinuation(UUID runId, String workerId) {
        var lease =
                leases.claimReady(workerId, 10).stream()
                        .filter(candidate -> runId.equals(candidate.runId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(lease.agentType()).isEqualTo("assistant");
        assertThat(lease.inputJson()).contains("continuation", "true");
        assertThat(leases.start(lease.attemptId(), workerId)).isTrue();
        assertThat(
                        leases.succeed(
                                lease.attemptId(),
                                workerId,
                                "{\"status\":\"succeeded\",\"summary\":\"final answer\"}"))
                .isTrue();
    }

    private List<DurableTaskLeaseService.TaskLease> claimAfterBarrier(
            String workerId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent claim barrier timed out");
        }
        return leases.claimReady(workerId, 1);
    }

    private void seedAgentAndSession(UUID agentId, UUID sessionId) {
        jdbc.update(
                "INSERT INTO agents (id, org_id, user_id, name, status) VALUES (?, ?, ?, ?, ?)",
                agentId,
                ORG_ID,
                USER_ID,
                "pg-task-" + agentId,
                "active");
        jdbc.update(
                "INSERT INTO chat_sessions (id, org_id, user_id, agent_id, title) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId,
                ORG_ID,
                USER_ID,
                agentId,
                "PG task integration");
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject(
                "SELECT status FROM assistant_runs WHERE id = ?", String.class, runId);
    }

    private void awaitRun(UUID runId, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(runStatus(runId))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not reach " + expected + " within " + timeout);
    }

    private Integer agentRunDepth(UUID agentRunId) {
        return jdbc.queryForObject(
                "SELECT depth FROM agent_runs WHERE id = ?", Integer.class, agentRunId);
    }

    private UUID agentRunParent(UUID agentRunId) {
        return jdbc.queryForObject(
                "SELECT parent_agent_run_id FROM agent_runs WHERE id = ?", UUID.class, agentRunId);
    }
}
