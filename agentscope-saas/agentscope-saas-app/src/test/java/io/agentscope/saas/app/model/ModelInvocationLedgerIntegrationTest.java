/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.OrchestrationGovernanceService;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope;
import io.agentscope.saas.domain.orchestration.OrchestrationGovernanceRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionOperations;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "saas.orchestration.input-token-cost-micros-per-million=1000000",
            "saas.orchestration.output-token-cost-micros-per-million=1000000",
            "saas.model.invocations.reconciliation-enabled=false",
            "saas.orchestration.deadline-sweep-fixed-delay-seconds=3600"
        })
@ActiveProfiles("local")
class ModelInvocationLedgerIntegrationTest {
    @Autowired ModelInvocationRepository repository;
    @Autowired OrchestrationGovernanceRepository budgets;
    @Autowired OrchestrationGovernanceService governance;
    @Autowired RunOrchestrationService runs;
    @Autowired ModelInvocationPolicyService policies;

    @Autowired
    @Qualifier("adminTransactionOperations")
    TransactionOperations transactions;

    private final TestDatabaseMapper database;
    private Scope scope;
    private ModelInvocationLedgerService ledger;
    private SaasProperties properties;

    @Autowired
    ModelInvocationLedgerIntegrationTest(@Qualifier("adminDataSource") DataSource source) {
        database = MyBatisRepositoryTestSupport.mapper(source, TestDatabaseMapper.class);
    }

    @BeforeEach
    void createScope() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        database.insertInvocationOrg(org, "invocations-" + org);
        database.insertInvocationUser(user, org, user + "@example.test");
        scope = new Scope(org, user, null, null, null, null, null);
        properties = new SaasProperties();
        properties.getOrchestration().setInputTokenCostMicrosPerMillion(1000000);
        properties.getOrchestration().setOutputTokenCostMicrosPerMillion(1000000);
        ledger =
                new ModelInvocationLedgerService(
                        repository, budgets, governance, transactions, properties);
        TenantContextHolder.setOrgId(org.toString());
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
    }

    @Test
    void abandonedCallIsSettledConservativelyOnceWithoutReplayingItsRun() {
        Scope runScope = runScope(100);
        var abandoned = admit(runScope, 10, 30);
        var live = admit(scope, 1, 5);
        database.expireModelInvocation(
                scope.orgId(), abandoned.id(), OffsetDateTime.now().minusMinutes(2));
        ledger.reconcileExpired(OffsetDateTime.now().minusSeconds(60), 100);
        var receipt = repository.find(scope.orgId(), abandoned.id()).orElseThrow();
        assertThat(receipt.status()).isEqualTo("FAILED");
        assertThat(receipt.reasonCode()).isEqualTo("MODEL_INVOCATION_ABANDONED");
        assertThat(receipt.usageSource()).isEqualTo("ESTIMATED");
        assertThat(receipt.totalTokens()).isEqualTo(40);
        assertThat(database.runState(runScope.runId()).status()).isEqualTo("RUNNING");
        assertThat(database.runState(runScope.runId()).consumedTokens()).isEqualTo(40);
        assertThat(repository.find(scope.orgId(), live.id()).orElseThrow().status())
                .isEqualTo("STARTED");
        ledger.reconcileExpired(OffsetDateTime.now().minusSeconds(60), 100);
        ledger.settle(runScope, abandoned.id(), "SUCCEEDED", 10, 2, 12, true, null);
        assertThat(metric("tokens_total")).isEqualTo(40);
        assertThat(metric("model_calls")).isEqualTo(2);
        assertThat(repository.find(UUID.randomUUID(), abandoned.id())).isEmpty();
    }

    @Test
    void concurrentSweepersCannotDoubleSettleAnAbandonedInvocation() throws Exception {
        var abandoned = admit(scope, 10, 30);
        database.expireModelInvocation(
                scope.orgId(), abandoned.id(), OffsetDateTime.now().minusMinutes(2));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return ledger.reconcileExpired(
                                        OffsetDateTime.now().minusSeconds(60), 100);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return ledger.reconcileExpired(
                                        OffsetDateTime.now().minusSeconds(60), 100);
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(metric("tokens_total")).isEqualTo(40);
            assertThat(metric("model_calls")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateSettlementDoesNotDuplicateCounters() {
        var admitted = admit(scope, 10, 30);
        assertThat(admitted.permitted()).isTrue();
        assertThat(metric("model_calls")).isEqualTo(1);
        assertThat(metric("tokens_total")).isZero();
        assertThat(ledger.settle(scope, admitted.id(), "SUCCEEDED", 10, 5, 15, true, null))
                .isTrue();
        assertThat(ledger.settle(scope, admitted.id(), "SUCCEEDED", 100, 50, 150, true, null))
                .isTrue();
        assertThat(metric("tokens_total")).isEqualTo(15);
        assertThat(metric("cost_micros")).isEqualTo(15);
        assertThat(repository.find(scope.orgId(), admitted.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void purposePoliciesAreTenantScopedAndUseOptimisticConcurrency() {
        var saved =
                policies.update(
                        scope.orgId(),
                        scope.userId(),
                        "COMPACTION",
                        new ModelInvocationPolicyService.PolicyCommand(
                                "default", 2000, 200, 30, "defaults-v1"));
        assertThat(repository.policy(scope.orgId(), "COMPACTION").orElseThrow()).isEqualTo(saved);
        assertThat(repository.policy(UUID.randomUUID(), "COMPACTION")).isEmpty();
        assertThatThrownBy(
                        () ->
                                policies.update(
                                        scope.orgId(),
                                        scope.userId(),
                                        "COMPACTION",
                                        new ModelInvocationPolicyService.PolicyCommand(
                                                null, 2000, 200, 30, "defaults-v1")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
        var inherited =
                policies.update(
                        scope.orgId(),
                        scope.userId(),
                        "COMPACTION",
                        new ModelInvocationPolicyService.PolicyCommand(
                                null, 2000, 200, 30, saved.version()));
        assertThat(inherited.modelId()).isNull();
        assertThat(inherited.version()).isNotEqualTo(saved.version());
    }

    @Test
    void purposePoliciesRejectCrossTenantActorAndUnknownModel() {
        assertThatThrownBy(
                        () ->
                                policies.update(
                                        scope.orgId(),
                                        UUID.randomUUID(),
                                        "VERIFY",
                                        new ModelInvocationPolicyService.PolicyCommand(
                                                null, 2000, 200, 30, "defaults-v1")))
                .hasMessageContaining("403");
        assertThatThrownBy(
                        () ->
                                policies.update(
                                        scope.orgId(),
                                        scope.userId(),
                                        "VERIFY",
                                        new ModelInvocationPolicyService.PolicyCommand(
                                                "missing-model", 2000, 200, 30, "defaults-v1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.policy(scope.orgId(), "VERIFY")).isEmpty();
    }

    @Test
    void inFlightReservationsPreventConcurrentQuotaBypass() throws Exception {
        properties.getModel().getInvocations().setMaxDailyOrgTokens(25L);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return admit(scope, 10, 10);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return admit(scope, 10, 10);
                            });
            start.countDown();
            var results =
                    java.util.List.of(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results)
                    .filteredOn(ModelInvocationLedgerService.Admission::permitted)
                    .hasSize(1);
            assertThat(metric("model_calls")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legacyUsageAndMonthlyTierQuotaAreIncludedAndOutputIsClamped() {
        repository.recordMetric(scope, "tokens_total", 80, "legacy");
        var result = ledger.admit(scope, "MEMORY_EXTRACT", "test", "v1", 10, 30, deadline(), 100);
        assertThat(result.permitted()).isTrue();
        assertThat(result.outputTokens()).isEqualTo(10);
        assertThat(admit(scope, 1, 1).permitted())
                .isTrue(); // unlimited tier for this independent request
        assertThat(
                        ledger.admit(scope, "MEMORY_EXTRACT", "test", "v1", 1, 1, deadline(), 100)
                                .permitted())
                .isFalse();
    }

    @Test
    void deactivatedOrganizationCannotPublishSuccessButUsageStillSettles() {
        var admitted = admit(scope, 10, 20);
        database.deactivateInvocationOrg(scope.orgId());
        assertThat(ledger.settle(scope, admitted.id(), "SUCCEEDED", 10, 5, 15, true, null))
                .isFalse();
        assertThat(metric("tokens_total")).isEqualTo(15);
        assertThat(repository.find(scope.orgId(), admitted.id()).orElseThrow().status())
                .isEqualTo("CANCELLED");
        assertThat(admit(scope, 1, 1).permitted()).isFalse();
    }

    @Test
    void failedCallsKeepPartialUsageAndConsumedCallSlot() {
        properties.getModel().getInvocations().setMaxDailyUserCalls(1L);
        var result = admit(scope, 10, 30);
        assertThat(ledger.settle(scope, result.id(), "FAILED", 10, 2, 12, true, "TransportFailure"))
                .isFalse();
        assertThat(metric("tokens_total")).isEqualTo(12);
        assertThat(admit(scope, 1, 1).rejection())
                .isEqualTo("MODEL_INVOCATION_CALL_QUOTA_EXCEEDED");
    }

    @Test
    void runBudgetIsReservedAndChargedExactlyOnce() {
        Scope runScope = runScope(30);
        var first = admit(runScope, 10, 30);
        assertThat(first.permitted()).isTrue();
        assertThat(first.outputTokens()).isEqualTo(20);
        assertThat(admit(runScope, 1, 1).permitted()).isFalse();
        assertThat(ledger.settle(runScope, first.id(), "SUCCEEDED", 10, 5, 15, true, null))
                .isTrue();
        assertThat(database.runState(runScope.runId()).consumedTokens()).isEqualTo(15);
        assertThat(database.runState(runScope.runId()).consumedModelCalls()).isEqualTo(1);
        ledger.settle(runScope, first.id(), "SUCCEEDED", 10, 5, 15, true, null);
        assertThat(database.runState(runScope.runId()).consumedTokens()).isEqualTo(15);
    }

    @Test
    void wrongUserCannotInvokeAnotherUsersRunOrSettleReceipt() {
        Scope runScope = runScope(100);
        UUID other = UUID.randomUUID();
        database.insertInvocationUser(other, scope.orgId(), other + "@example.test");
        Scope forged =
                new Scope(
                        scope.orgId(),
                        other,
                        runScope.runId(),
                        runScope.taskId(),
                        runScope.agentRunId(),
                        null,
                        null);
        assertThat(admit(forged, 1, 1).rejection()).isEqualTo("MODEL_INVOCATION_SCOPE_EXPIRED");
        var admitted = admit(scope, 1, 1);
        assertThatThrownBy(
                        () ->
                                ledger.settle(
                                        forged, admitted.id(), "SUCCEEDED", 1, 1, 2, true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope mismatch");
        assertThat(repository.find(scope.orgId(), admitted.id()).orElseThrow().status())
                .isEqualTo("STARTED");
    }

    @Test
    void leaseLossPreventsSuccessButStillRecordsConsumedResources() {
        Scope runScope = runScope(100);
        var result = admit(runScope, 10, 20);
        database.updateRunDeadline(runScope.runId(), OffsetDateTime.now().minusSeconds(1));
        governance.expireDue(100);
        assertThat(ledger.settle(runScope, result.id(), "SUCCEEDED", 10, 3, 13, true, null))
                .isFalse();
        assertThat(repository.find(scope.orgId(), result.id()).orElseThrow().status())
                .isEqualTo("CANCELLED");
        assertThat(database.runState(runScope.runId()).consumedTokens()).isEqualTo(13);
        assertThat(database.runState(runScope.runId()).status()).isEqualTo("FAILED");
    }

    @Test
    void providerOverrunFailsRunAndReceiptWithoutDoubleCharging() {
        Scope runScope = runScope(20);
        var result = admit(runScope, 10, 10);
        assertThat(ledger.settle(runScope, result.id(), "SUCCEEDED", 10, 20, 30, true, null))
                .isFalse();
        var receipt = repository.find(scope.orgId(), result.id()).orElseThrow();
        assertThat(receipt.status()).isEqualTo("FAILED");
        assertThat(receipt.reasonCode()).isEqualTo("RUN_TOKEN_BUDGET_EXCEEDED");
        assertThat(database.runState(runScope.runId()).consumedTokens()).isEqualTo(30);
        assertThat(ledger.settle(runScope, result.id(), "SUCCEEDED", 10, 20, 30, true, null))
                .isFalse();
        assertThat(metric("tokens_total")).isEqualTo(30);
    }

    private Scope runScope(long tokenBudget) {
        UUID agent = UUID.randomUUID(), session = UUID.randomUUID();
        database.insertAgent(agent, scope.orgId(), scope.userId(), "invocation-" + agent);
        database.insertChatSession(
                session, scope.orgId(), scope.userId(), agent, "Invocation budget");
        var tenant =
                new TenantContext(
                        scope.orgId().toString(),
                        scope.userId().toString(),
                        "member",
                        "standard",
                        2,
                        0);
        var handle =
                runs.createDirectRun(
                        tenant,
                        agent,
                        session,
                        null,
                        "Test call",
                        null,
                        new RunOrchestrationService.RunPolicy(
                                tokenBudget, 100, 10, 300, tokenBudget, 100, 10, 300, "{}"));
        return new Scope(
                scope.orgId(),
                scope.userId(),
                handle.runId(),
                handle.rootTaskId(),
                handle.rootAgentRunId(),
                null,
                null);
    }

    private ModelInvocationLedgerService.Admission admit(Scope owner, int input, int output) {
        return ledger.admit(
                owner, "COMPACTION", "test", "route:policy", input, output, deadline(), 0);
    }

    private static OffsetDateTime deadline() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
    }

    private long metric(String name) {
        return database.invocationMetric(scope.orgId(), scope.userId(), name);
    }
}
