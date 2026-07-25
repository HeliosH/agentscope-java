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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.orchestration.RunOrchestrationService;
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

/** Verifies atomic budget enforcement and immutable parent-to-child permission inheritance. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "saas.orchestration.input-token-cost-micros-per-million=1000000",
            "saas.orchestration.output-token-cost-micros-per-million=1000000",
            "saas.orchestration.deadline-sweep-fixed-delay-seconds=3600"
        })
@ActiveProfiles("local")
class OrchestrationGovernanceIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired RunOrchestrationService runs;
    @Autowired OrchestrationGovernanceService governance;
    @Autowired ObjectMapper objectMapper;

    private final JdbcTemplate jdbc;

    @Autowired
    OrchestrationGovernanceIntegrationTest(
            @Qualifier("adminDataSource") DataSource adminDataSource) {
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
    void chargesActualUsageAndTerminatesTheWholeRunWhenBudgetIsExceeded() throws Exception {
        Fixture fixture = createGovernedRun(10);
        var child =
                runs.createSubagentTask(
                        tenant(),
                        fixture.agentId(),
                        fixture.runId(),
                        fixture.rootAgentRunId(),
                        "governed-child",
                        "researcher",
                        "governed-child-session",
                        "{\"prompt\":\"governed work\"}",
                        new RunOrchestrationService.SubagentPolicy(3, 8, 32, 10, 100, 3, 300));

        var parentSnapshot =
                governance.permissionSnapshot(ORG_ID, fixture.runId(), fixture.rootAgentRunId());
        var childSnapshot =
                governance.permissionSnapshot(ORG_ID, fixture.runId(), child.agentRunId());
        assertThat(childSnapshot).isEqualTo(parentSnapshot);

        assertThat(
                        governance
                                .consume(ORG_ID, fixture.runId(), fixture.rootAgentRunId(), 2, 2, 4)
                                .permitted())
                .isTrue();
        var rejected =
                governance.consume(ORG_ID, fixture.runId(), fixture.rootAgentRunId(), 5, 2, 7);

        assertThat(rejected.permitted()).isFalse();
        assertThat(rejected.reason()).isEqualTo("RUN_TOKEN_BUDGET_EXCEEDED");
        assertThat(longValue("consumed_tokens", fixture.runId())).isEqualTo(11);
        assertThat(longValue("consumed_cost_micros", fixture.runId())).isEqualTo(11);
        assertThat(intValue("consumed_model_calls", fixture.runId())).isEqualTo(2);
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM run_events WHERE run_id = ? ORDER BY seq",
                                String.class,
                                fixture.runId()))
                .contains("AGENT_PERMISSION_SNAPSHOT", "SUBAGENT_PERMISSION_INHERITED")
                .endsWith("RUN_BUDGET_EXCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM agent_runs WHERE id = ?",
                                String.class,
                                child.agentRunId()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void concurrentConsumersCannotBypassTheSharedRunBudget() throws Exception {
        Fixture fixture = createGovernedRun(10);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrchestrationGovernanceService.BudgetDecision> first =
                    pool.submit(() -> consumeAfterBarrier(fixture, ready, start));
            Future<OrchestrationGovernanceService.BudgetDecision> second =
                    pool.submit(() -> consumeAfterBarrier(fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var decisions =
                    java.util.List.of(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(decisions)
                    .filteredOn(OrchestrationGovernanceService.BudgetDecision::permitted)
                    .hasSize(1);
            assertThat(decisions)
                    .filteredOn(decision -> !decision.permitted())
                    .singleElement()
                    .extracting(OrchestrationGovernanceService.BudgetDecision::reason)
                    .isEqualTo("RUN_TOKEN_BUDGET_EXCEEDED");
            assertThat(longValue("consumed_tokens", fixture.runId())).isEqualTo(12);
            assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadlineSweepTerminatesQueuedOrStalledWork() throws Exception {
        Fixture fixture = createGovernedRun(10);
        jdbc.update(
                "UPDATE assistant_runs SET deadline_at = ? WHERE id = ?",
                java.time.OffsetDateTime.now().minusSeconds(1),
                fixture.runId());

        assertThat(governance.expireDue(10)).isGreaterThanOrEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_code FROM assistant_runs WHERE id = ?",
                                String.class,
                                fixture.runId()))
                .isEqualTo("RUN_DEADLINE_EXCEEDED");
    }

    private OrchestrationGovernanceService.BudgetDecision consumeAfterBarrier(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Budget concurrency barrier timed out");
        }
        return governance.consume(ORG_ID, fixture.runId(), fixture.rootAgentRunId(), 3, 3, 6);
    }

    private Fixture createGovernedRun(long tokenBudget) throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO agents (id, org_id, user_id, name, status) VALUES (?, ?, ?, ?, ?)",
                agentId,
                ORG_ID,
                USER_ID,
                "governance-" + agentId,
                "active");
        jdbc.update(
                "INSERT INTO chat_sessions (id, org_id, user_id, agent_id, title) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId,
                ORG_ID,
                USER_ID,
                agentId,
                "Governance integration");
        PermissionContextState permissions =
                PermissionContextState.builder()
                        .addDenyRule(
                                "execute",
                                new PermissionRule(
                                        "execute",
                                        null,
                                        PermissionBehavior.DENY,
                                        "integration-test"))
                        .build();
        String permissionJson = objectMapper.writeValueAsString(permissions);
        var handle =
                runs.createDirectRun(
                        tenant(),
                        agentId,
                        sessionId,
                        null,
                        "Govern this run",
                        null,
                        new RunOrchestrationService.RunPolicy(
                                tokenBudget,
                                100,
                                3,
                                300,
                                tokenBudget,
                                100,
                                3,
                                300,
                                permissionJson));
        return new Fixture(agentId, handle.runId(), handle.rootAgentRunId());
    }

    private static TenantContext tenant() {
        return new TenantContext(
                ORG_ID.toString(), USER_ID.toString(), "member", "standard", 2, 100_000);
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject(
                "SELECT status FROM assistant_runs WHERE id = ?", String.class, runId);
    }

    private long longValue(String column, UUID runId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM assistant_runs WHERE id = ?", Long.class, runId);
    }

    private int intValue(String column, UUID runId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM assistant_runs WHERE id = ?", Integer.class, runId);
    }

    private record Fixture(UUID agentId, UUID runId, UUID rootAgentRunId) {}
}
