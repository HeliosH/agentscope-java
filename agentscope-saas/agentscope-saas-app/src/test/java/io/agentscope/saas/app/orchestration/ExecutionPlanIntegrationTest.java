/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.ResourceBudget;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.TaskSpec;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.ExecutionPlanService;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifies V28, MyBatis adapters, plan approval, and DAG activation as one transaction flow. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "saas.orchestration.planner-enabled=true",
            "saas.orchestration.scheduler-enabled=false",
            "saas.orchestration.deadline-sweep-fixed-delay-seconds=3600"
        })
@ActiveProfiles("local")
class ExecutionPlanIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired RunOrchestrationService runs;
    @Autowired ExecutionPlanService plans;
    @Autowired HarnessAgent agent;

    private final TestDatabaseMapper database;

    @Autowired
    ExecutionPlanIntegrationTest(@Qualifier("adminDataSource") DataSource adminDataSource) {
        this.database =
                MyBatisRepositoryTestSupport.mapper(adminDataSource, TestDatabaseMapper.class);
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
    void publishesApprovesAndActivatesOnlyDependencyRoots() {
        assertThat(agent.getToolkit().getTool(PlanPublishTool.NAME)).isNotNull();
        Fixture fixture = createRun();
        var published = plans.publish(tenant(), fixture.agentId(), fixture.runId(), parallelPlan());

        assertThat(published.version()).isEqualTo(1);
        assertThat(published.status()).isEqualTo("PROPOSED");
        assertThat(published.tasks()).hasSize(3).allMatch(task -> "PENDING".equals(task.status()));
        assertThat(published.edges()).hasSize(2);
        assertThat(database.runState(fixture.runId()).status()).isEqualTo("WAITING_APPROVAL");

        var approval =
                plans.decide(
                        tenant(),
                        fixture.agentId(),
                        fixture.runId(),
                        published.planId(),
                        "APPROVE",
                        "Proceed",
                        "approve-" + fixture.runId());
        var active = plans.latest(tenant(), fixture.agentId(), fixture.runId()).orElseThrow();

        assertThat(approval.activated()).isTrue();
        assertThat(active.status()).isEqualTo("APPROVED");
        assertThat(active.tasks())
                .filteredOn(
                        task -> List.of("research-a", "research-b").contains(task.clientTaskId()))
                .allMatch(task -> "READY".equals(task.status()));
        assertThat(active.tasks())
                .filteredOn(task -> "synthesize".equals(task.clientTaskId()))
                .singleElement()
                .extracting(task -> task.status())
                .isEqualTo("PENDING");
        assertThat(database.runState(fixture.runId()).status()).isEqualTo("RUNNING");
        assertThat(database.runEventTypes(fixture.runId()))
                .contains("PLAN_PROPOSED", "APPROVAL_REQUIRED", "PLAN_APPROVED", "RUN_STARTED");
    }

    @Test
    void duplicatePublicationAndApprovalAreIdempotent() {
        Fixture fixture = createRun();
        ExecutionPlan plan = parallelPlan();

        var first = plans.publish(tenant(), fixture.agentId(), fixture.runId(), plan);
        var duplicate = plans.publish(tenant(), fixture.agentId(), fixture.runId(), plan);
        var approved =
                plans.decide(
                        tenant(),
                        fixture.agentId(),
                        fixture.runId(),
                        first.planId(),
                        "APPROVE",
                        null,
                        "same-decision");
        var replay =
                plans.decide(
                        tenant(),
                        fixture.agentId(),
                        fixture.runId(),
                        first.planId(),
                        "APPROVE",
                        null,
                        "same-decision");

        assertThat(duplicate.planId()).isEqualTo(first.planId());
        assertThat(duplicate.reused()).isTrue();
        assertThat(approved.reused()).isFalse();
        assertThat(replay.reused()).isTrue();
    }

    @Test
    void revisionReusesOnlyUnchangedSuccessfulTasksAndSupersedesPreviousPlan() {
        Fixture fixture = createRun();
        var first = plans.publish(tenant(), fixture.agentId(), fixture.runId(), parallelPlan());
        var firstResearch =
                first.tasks().stream()
                        .filter(task -> "research-a".equals(task.clientTaskId()))
                        .findFirst()
                        .orElseThrow();
        var firstPending =
                first.tasks().stream()
                        .filter(task -> "research-b".equals(task.clientTaskId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        database.markTaskSucceeded(
                                firstResearch.taskId(),
                                "{\"status\":\"succeeded\",\"summary\":\"done\"}"))
                .isEqualTo(1);

        ExecutionPlan revised =
                new ExecutionPlan(
                        "Prepare and review a verified report",
                        List.of(
                                task("research-a", List.of(), false),
                                task("research-b", List.of(), false),
                                task("synthesize", List.of("research-a", "research-b"), true),
                                task("review", List.of("synthesize"), false)),
                        true,
                        "Add independent review",
                        ResourceBudget.unlimited());
        var second = plans.publish(tenant(), fixture.agentId(), fixture.runId(), revised);

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.supersedesPlanId()).isEqualTo(first.planId());
        assertThat(second.tasks())
                .filteredOn(task -> "research-a".equals(task.clientTaskId()))
                .singleElement()
                .satisfies(
                        task -> {
                            assertThat(task.taskId()).isEqualTo(firstResearch.taskId());
                            assertThat(task.status()).isEqualTo("SUCCEEDED");
                        });
        assertThat(database.taskStatus(firstPending.taskId())).isEqualTo("CANCELLED");
        assertThat(database.runEventTypes(fixture.runId()))
                .filteredOn("PLAN_PROPOSED"::equals)
                .hasSize(2);
    }

    @Test
    void cancelsAPlanWaitingForApprovalAndDoesNotRepeatTerminalTransitions() {
        Fixture fixture = createRun();
        plans.publish(tenant(), fixture.agentId(), fixture.runId(), parallelPlan());

        var cancelled = runs.cancel(tenant(), fixture.agentId(), fixture.runId()).orElseThrow();
        var replay = runs.cancel(tenant(), fixture.agentId(), fixture.runId()).orElseThrow();

        assertThat(cancelled.interrupted()).isTrue();
        assertThat(replay.interrupted()).isFalse();
        assertThat(database.runState(fixture.runId()).status()).isEqualTo("CANCELLED");
        assertThat(database.runEventTypes(fixture.runId()))
                .filteredOn("RUN_CANCELLED"::equals)
                .hasSize(1);
    }

    private Fixture createRun() {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        database.insertAgent(agentId, ORG_ID, USER_ID, "plan-" + agentId);
        database.insertChatSession(sessionId, ORG_ID, USER_ID, agentId, "Plan integration");
        var handle =
                runs.createDirectRun(
                        tenant(),
                        agentId,
                        sessionId,
                        null,
                        "Create a structured plan",
                        "plan-run-" + agentId);
        return new Fixture(agentId, handle.runId());
    }

    private static ExecutionPlan parallelPlan() {
        return new ExecutionPlan(
                "Prepare a verified report",
                List.of(
                        task("research-a", List.of(), false),
                        task("research-b", List.of(), false),
                        task("synthesize", List.of("research-a", "research-b"), true)),
                true,
                "Research can run in parallel",
                ResourceBudget.unlimited());
    }

    private static TaskSpec task(String id, List<String> dependencies, boolean write) {
        return new TaskSpec(
                id,
                id,
                write ? "writer" : "researcher",
                dependencies,
                Map.of("prompt", id),
                List.of(id + "-output"),
                List.of("result is traceable"),
                WorkspaceIsolationMode.RUN_ISOLATED,
                write,
                false,
                true,
                0,
                2,
                "IDEMPOTENT",
                ResourceBudget.unlimited());
    }

    private static TenantContext tenant() {
        return new TenantContext(
                ORG_ID.toString(), USER_ID.toString(), "member", "standard", 2, 100_000);
    }

    private record Fixture(UUID agentId, UUID runId) {}
}
