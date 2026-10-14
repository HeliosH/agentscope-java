/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolContractSnapshot;
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.tool.ToolRetrySafety;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Decision;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

/** Exercises the core ToolExecutor through the database-backed journal boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class DurableToolExecutionJournalIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired RunOrchestrationService runs;
    @Autowired DurableToolExecutionJournalFactory journals;
    @Autowired DurableContextCheckpointFactory checkpoints;
    @Autowired ToolReconciliationService reconciliations;
    @Autowired io.agentscope.saas.domain.orchestration.ToolReconciliationRepository resolutions;
    @Autowired io.agentscope.saas.domain.repository.UserRepository users;
    @Autowired io.agentscope.saas.domain.orchestration.RunOrchestrationRepository runRepository;

    private final TestDatabaseMapper database;

    @Autowired
    @Qualifier("dataSource")
    DataSource tenantDataSource;

    @Autowired
    DurableToolExecutionJournalIntegrationTest(
            @Qualifier("adminDataSource") DataSource adminDataSource) {
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
    void committedToolResultIsReusedAcrossJournalInstances() {
        Fixture fixture = createRun("durable success reuse");
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new CountingTool(calls, ToolRetrySafety.NEVER, null, null));

        ToolResultBlock first =
                invoke(toolkit, fixture, "call-success", journals.create(ORG_ID, fixture.runId()));
        ToolResultBlock replay =
                invoke(toolkit, fixture, "call-success", journals.create(ORG_ID, fixture.runId()));

        assertThat(text(first)).isEqualTo("ok-1");
        assertThat(text(replay)).isEqualTo("ok-1");
        assertThat(calls).hasValue(1);
        assertThat(database.toolOperationState(fixture.runId(), "call-success"))
                .satisfies(
                        state -> {
                            assertThat(state.status()).isEqualTo("SUCCEEDED");
                            assertThat(state.retrySafety()).isEqualTo("NEVER");
                            assertThat(state.resultJson()).contains("ok-1", "success");
                        });
        assertThat(database.countToolOperationOutbox(fixture.runId(), "call-success")).isEqualTo(1);
    }

    @Test
    void unknownSideEffectIsPersistedAndCannotBeBlindlyReplayed() {
        Fixture fixture = createRun("durable unknown outcome");
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("external success, response lost"),
                                null));

        ToolResultBlock first =
                invoke(toolkit, fixture, "call-unknown", journals.create(ORG_ID, fixture.runId()));
        ToolResultBlock replay =
                invoke(toolkit, fixture, "call-unknown", journals.create(ORG_ID, fixture.runId()));

        assertThat(text(first)).contains("external success, response lost");
        assertThat(text(replay)).contains("verify its external outcome before retrying");
        assertThat(calls).hasValue(1);
        assertThat(database.toolOperationState(fixture.runId(), "call-unknown"))
                .satisfies(
                        state -> {
                            assertThat(state.status()).isEqualTo("OUTCOME_UNKNOWN");
                            assertThat(state.errorType()).contains("IllegalStateException");
                            assertThat(state.errorMessage())
                                    .isEqualTo("external success, response lost");
                        });
        assertThat(database.countToolOperationOutbox(fixture.runId(), "call-unknown")).isEqualTo(1);
    }

    @Test
    void safeFailureCanBeRetriedFromTheDurableJournal() {
        Fixture fixture = createRun("durable safe retry");
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.READ_ONLY,
                                new IllegalStateException("temporary read failure"),
                                null));

        ToolResultBlock first =
                invoke(toolkit, fixture, "call-retry", journals.create(ORG_ID, fixture.runId()));
        ToolResultBlock retry =
                invoke(toolkit, fixture, "call-retry", journals.create(ORG_ID, fixture.runId()));

        assertThat(text(first)).contains("temporary read failure");
        assertThat(text(retry)).isEqualTo("ok-2");
        assertThat(calls).hasValue(2);
        assertThat(database.toolOperationState(fixture.runId(), "call-retry").status())
                .isEqualTo("SUCCEEDED");
        assertThat(database.countToolOperationOutbox(fixture.runId(), "call-retry")).isEqualTo(2);
    }

    @Test
    void duplicateTerminalCommitIsIdempotent() {
        Fixture fixture = createRun("durable duplicate commit");
        String callId = "call-duplicate-commit";
        ToolExecutionJournal journal = journals.create(ORG_ID, fixture.runId());
        ToolExecutionJournal.Invocation invocation = invocation(fixture, callId);
        ToolResultBlock result = ToolResultBlock.text("committed-once");

        assertThat(journal.prepare(invocation).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        journal.markRunning(invocation);
        journal.complete(invocation, ToolExecutionJournal.TerminalStatus.SUCCEEDED, result, null);
        journal.complete(invocation, ToolExecutionJournal.TerminalStatus.SUCCEEDED, result, null);

        assertThat(database.toolOperationState(fixture.runId(), callId).status())
                .isEqualTo("SUCCEEDED");
        assertThat(database.countToolOperationOutbox(fixture.runId(), callId)).isEqualTo(1);
    }

    @Test
    void contextCheckpointRevisionsAreMonotonicAndLeaseFenced() {
        Fixture fixture = createRun("durable context checkpoint");
        StepSnapshot.Identity identity = identity(fixture);
        ExecutionLeaseSnapshot lease = new ExecutionLeaseSnapshot("direct:" + fixture.runId());
        ContextCheckpointStore store = checkpoints.create(ORG_ID, fixture.runId());

        ContextCheckpointStore.StoredCheckpoint first =
                store.save(
                        new ContextCheckpointStore.Draft(
                                identity,
                                lease,
                                "step-checkpoint-1",
                                "1".repeat(64),
                                "summary-one",
                                List.of(),
                                null,
                                List.of(fixture.runId() + ":pending-1"),
                                "workspace-v1"));
        ContextCheckpointStore.StoredCheckpoint second =
                store.save(
                        new ContextCheckpointStore.Draft(
                                identity,
                                lease,
                                "step-checkpoint-2",
                                "2".repeat(64),
                                "summary-two",
                                List.of(),
                                "facts-v1",
                                List.of(),
                                "workspace-v2"));

        assertThat(first.historyRevision()).isEqualTo(1);
        assertThat(second.historyRevision()).isEqualTo(2);
        assertThat(database.countContextCheckpoints(fixture.runId(), fixture.agentRunId()))
                .isEqualTo(2);
        assertThat(database.latestContextCheckpoint(fixture.runId(), fixture.agentRunId()))
                .satisfies(
                        checkpoint -> {
                            assertThat(checkpoint.historyRevision()).isEqualTo(2);
                            assertThat(checkpoint.attemptId()).isEqualTo(fixture.attemptId());
                            assertThat(checkpoint.stepId()).isEqualTo("step-checkpoint-2");
                            assertThat(checkpoint.historyHash()).isEqualTo("2".repeat(64));
                            assertThat(checkpoint.summary()).isEqualTo("summary-two");
                            assertThat(checkpoint.retainedTailJson()).contains("[]");
                            assertThat(checkpoint.pendingOperationsJson()).contains("[]");
                            assertThat(checkpoint.workspaceVersion()).isEqualTo("workspace-v2");
                        });

        assertThat(
                        database.updateAttemptExpiry(
                                fixture.attemptId(), OffsetDateTime.now().minusSeconds(1)))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                store.save(
                                        new ContextCheckpointStore.Draft(
                                                identity,
                                                lease,
                                                "step-checkpoint-3",
                                                "3".repeat(64),
                                                "stale",
                                                List.of(),
                                                null,
                                                List.of(),
                                                null)))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(database.countContextCheckpoints(fixture.runId(), fixture.agentRunId()))
                .isEqualTo(2);
    }

    @Test
    void concurrentClaimDoesNotExecuteTheSameBusinessOperationTwice() throws Exception {
        Fixture fixture = createRun("durable concurrent claim");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Toolkit toolkit =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                null,
                                new Barriers(entered, release)));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first =
                    pool.submit(
                            () ->
                                    invoke(
                                            toolkit,
                                            fixture,
                                            "call-concurrent",
                                            journals.create(ORG_ID, fixture.runId())));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ToolResultBlock concurrent =
                    invoke(
                            toolkit,
                            fixture,
                            "call-concurrent",
                            journals.create(ORG_ID, fixture.runId()));

            assertThat(text(concurrent)).contains("verify its external outcome before retrying");
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(text(first.get(5, TimeUnit.SECONDS))).isEqualTo("ok-1");
            assertThat(database.toolOperationState(fixture.runId(), "call-concurrent").status())
                    .isEqualTo("SUCCEEDED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCannotCommitAndReplacementAttemptResumesSafeOperation() throws Exception {
        Fixture original = createRun("durable expired lease recovery");
        String firstWorker = "worker-a-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(
                        database.activateAttemptLease(
                                original.attemptId(), firstWorker, now.plusMinutes(5), now))
                .isEqualTo(1);

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Toolkit toolkit =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.READ_ONLY,
                                null,
                                new Barriers(entered, release)));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first =
                    pool.submit(
                            () ->
                                    invoke(
                                            toolkit,
                                            original,
                                            "call-expired",
                                            journals.create(ORG_ID, original.runId()),
                                            firstWorker));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(
                            database.updateAttemptExpiry(
                                    original.attemptId(), OffsetDateTime.now().minusSeconds(1)))
                    .isEqualTo(1);
            release.countDown();

            assertThat(text(first.get(5, TimeUnit.SECONDS)))
                    .contains("Execution lease is no longer valid");
            assertThat(database.toolOperationState(original.runId(), "call-expired").status())
                    .isEqualTo("OUTCOME_UNKNOWN");

            UUID replacementAttemptId = UUID.randomUUID();
            String replacementWorker = "worker-b-" + UUID.randomUUID();
            OffsetDateTime replacementNow = OffsetDateTime.now();
            assertThat(
                            database.insertRunningAttempt(
                                    replacementAttemptId,
                                    ORG_ID,
                                    original.runId(),
                                    original.taskId(),
                                    original.agentRunId(),
                                    2,
                                    replacementWorker,
                                    replacementNow.plusMinutes(5),
                                    "replacement:" + replacementAttemptId,
                                    replacementNow))
                    .isEqualTo(1);
            Fixture replacement =
                    new Fixture(
                            original.runId(),
                            original.taskId(),
                            original.agentRunId(),
                            replacementAttemptId,
                            original.sessionId());

            ToolResultBlock resumed =
                    invoke(
                            toolkit,
                            replacement,
                            "call-expired",
                            journals.create(ORG_ID, original.runId()),
                            replacementWorker);

            assertThat(text(resumed)).isEqualTo("ok-2");
            assertThat(calls).hasValue(2);
            assertThat(database.toolOperationState(original.runId(), "call-expired"))
                    .satisfies(
                            state -> {
                                assertThat(state.status()).isEqualTo("SUCCEEDED");
                                assertThat(state.attemptId()).isEqualTo(replacementAttemptId);
                            });
            assertThat(database.countToolOperationOutbox(original.runId(), "call-expired"))
                    .isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static TenantContext administrator() {
        return new TenantContext(
                ORG_ID.toString(),
                "00000000-0000-0000-0000-0000000000a1",
                "admin",
                "enterprise",
                10,
                100000);
    }

    private ToolReconciliationService.OperationView operation(Fixture fixture) {
        var scope = resolutions.run(ORG_ID, fixture.runId()).orElseThrow();
        return reconciliations
                .ownedOperations(tenant(), scope.agentId(), fixture.runId(), null, 25)
                .items()
                .get(0);
    }

    private static ToolReconciliationService.Request evidence(
            ToolReconciliationService.OperationView op,
            io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Decision decision,
            boolean approve) {
        return new ToolReconciliationService.Request(
                op.runVersion(),
                op.version(),
                op.resolutionVersion(),
                decision,
                "Verified in the owned external fixture",
                "fixture://receipt/" + op.id(),
                decision
                                == io.agentscope.saas.domain.orchestration
                                        .ToolReconciliationRepository.Decision.CONFIRMED_SUCCESS
                        ? "externally confirmed"
                        : null,
                approve);
    }

    @Test
    void externallyConfirmedSuccessIsReusedWithoutOverwritingTheUnknownFact() {
        var fixture = createRun("confirmed external success");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("response lost"),
                                null));
        invoke(tool, fixture, "confirm-success", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        var request =
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.CONFIRMED_SUCCESS,
                        false);
        var confirmed =
                reconciliations.reconcile(
                        administrator(),
                        fixture.runId(),
                        op.id(),
                        "success-" + UUID.randomUUID(),
                        request);
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "confirm-success",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("externally confirmed");
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "confirm-success",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("externally confirmed");
        assertThat(calls).hasValue(1);
        assertThat(operation(fixture).status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(operation(fixture).effectiveStatus()).isEqualTo("CONFIRMED_SUCCESS");
        assertThat(operation(fixture).version()).isEqualTo(op.version());
        var history = reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).id()).isEqualTo(confirmed.id());
        assertThat(
                        resolutions
                                .latest(ORG_ID, fixture.runId(), op.id())
                                .orElseThrow()
                                .sourceFactJson())
                .contains("response lost", "OUTCOME_UNKNOWN");
    }

    @Test
    void reconciliationCommandIsIdempotentAndRejectsDifferentEvidenceOrOldVersions() {
        var fixture = createRun("idempotent reconciliation");
        invoke(
                toolkit(
                        new CountingTool(
                                new AtomicInteger(),
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null)),
                fixture,
                "idem",
                journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        var request =
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.UNRESOLVED,
                        false);
        String key = "evidence-" + UUID.randomUUID();
        var first =
                reconciliations.reconcile(administrator(), fixture.runId(), op.id(), key, request);
        var again =
                reconciliations.reconcile(administrator(), fixture.runId(), op.id(), key, request);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.reused()).isTrue();
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        administrator(),
                                        fixture.runId(),
                                        op.id(),
                                        key,
                                        evidence(
                                                op,
                                                io.agentscope.saas.domain.orchestration
                                                        .ToolReconciliationRepository.Decision
                                                        .CONFIRMED_SUCCESS,
                                                false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        administrator(),
                                        fixture.runId(),
                                        op.id(),
                                        "old-" + UUID.randomUUID(),
                                        request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .hasSize(1);
    }

    @Test
    void nonExecutionEvidenceRequiresExplicitApprovalAndGrantsOneReplay() {
        var fixture = createRun("approved non-execution");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("not dispatched"),
                                null));
        invoke(tool, fixture, "approved", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "no-approval-" + UUID.randomUUID(),
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.CONFIRMED_NOT_PERFORMED,
                        false));
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "approved",
                                        journals.create(ORG_ID, fixture.runId()))))
                .contains("explicit");
        assertThat(calls).hasValue(1);
        op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "approval-" + UUID.randomUUID(),
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.CONFIRMED_NOT_PERFORMED,
                        true));
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "approved",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("ok-2");
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "approved",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("ok-2");
        assertThat(calls).hasValue(2);
        assertThat(operation(fixture).status()).isEqualTo("SUCCEEDED");
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .hasSize(2);
        assertThat(
                        resolutions
                                .latest(ORG_ID, fixture.runId(), op.id())
                                .orElseThrow()
                                .sourceFactJson())
                .contains("OUTCOME_UNKNOWN", "not dispatched");
    }

    @Test
    void revokedAdministratorCannotResolveOrUseAnUnconsumedApproval() {
        var fixture = createRun("revoked approval");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("not dispatched"),
                                null));
        invoke(tool, fixture, "revoked", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "approval-" + UUID.randomUUID(),
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.CONFIRMED_NOT_PERFORMED,
                        true));
        var admin =
                users.findByOrgIdAndId(ORG_ID, UUID.fromString(administrator().userId()))
                        .orElseThrow();
        String role = admin.getRole();
        try {
            admin.setRole("member");
            users.save(admin);
            assertThat(
                            text(
                                    invoke(
                                            tool,
                                            fixture,
                                            "revoked",
                                            journals.create(ORG_ID, fixture.runId()))))
                    .contains("explicit");
            assertThat(calls).hasValue(1);
            assertThatThrownBy(
                            () ->
                                    reconciliations.organizationOperations(
                                            administrator(), "ALL", null, 25))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally {
            admin.setRole(role);
            users.save(admin);
        }
    }

    @Test
    void unresolvedEvidenceDoesNotAllowASafeReadToIgnoreAdministratorHold() {
        var fixture = createRun("unresolved safe read");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null));
        invoke(tool, fixture, "held", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "held-" + UUID.randomUUID(),
                evidence(
                        op,
                        io.agentscope.saas.domain.orchestration.ToolReconciliationRepository
                                .Decision.UNRESOLVED,
                        false));
        assertThat(
                        text(
                                invoke(
                                        toolkit(
                                                new CountingTool(
                                                        calls,
                                                        ToolRetrySafety.READ_ONLY,
                                                        null,
                                                        null)),
                                        fixture,
                                        "held",
                                        journals.create(ORG_ID, fixture.runId()))))
                .contains("contract");
        assertThat(calls).hasValue(1);
    }

    @Test
    void employeeCannotResolveAndOtherOwnerCannotReadOperationExistence() {
        var fixture = createRun("reconciliation authorization");
        invoke(
                toolkit(
                        new CountingTool(
                                new AtomicInteger(),
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null)),
                fixture,
                "scope",
                journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        tenant(),
                                        fixture.runId(),
                                        op.id(),
                                        "member-" + UUID.randomUUID(),
                                        evidence(
                                                op,
                                                io.agentscope.saas.domain.orchestration
                                                        .ToolReconciliationRepository.Decision
                                                        .CONFIRMED_SUCCESS,
                                                false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var source = resolutions.run(ORG_ID, fixture.runId()).orElseThrow();
        assertThatThrownBy(
                        () ->
                                reconciliations.ownedOperations(
                                        administrator(),
                                        source.agentId(),
                                        fixture.runId(),
                                        null,
                                        25))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(resolutions.history(ORG_ID, fixture.runId(), op.id(), 0, 25)).isEmpty();
    }

    @Test
    void replayPermitExpiresAndCannotBeUsedWithChangedIntent() {
        var fixture = createRun("expired replay permit");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null));
        invoke(tool, fixture, "expires", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "expires-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        var original = invocation(fixture, "expires");
        var different =
                new ToolExecutionJournal.Invocation(
                        original.operationId(),
                        original.invocationId(),
                        original.stepId(),
                        original.identity(),
                        original.executionLease(),
                        original.toolCallId(),
                        original.toolName(),
                        StepSnapshot.fingerprint(Map.of("value", "different")),
                        Map.of("value", "different"),
                        original.retrySafety(),
                        original.toolContract(),
                        original.logicalOriginId());
        assertThatThrownBy(() -> journals.create(ORG_ID, fixture.runId()).prepare(different))
                .isInstanceOf(IllegalStateException.class);
        assertThat(database.consumedToolReplay(op.id())).isZero();
        assertThat(database.expireToolReplay(op.id(), OffsetDateTime.now().minusSeconds(1)))
                .isEqualTo(1);
        assertThat(text(invoke(tool, fixture, "expires", journals.create(ORG_ID, fixture.runId()))))
                .contains("unexpired");
        assertThat(calls).hasValue(1);
        assertThat(database.consumedToolReplay(op.id())).isZero();
    }

    @Test
    void cancellationPreventsApprovedReplayAndDispatchAfterClaim() {
        var fixture = createRun("cancelled replay");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null));
        invoke(tool, fixture, "cancel", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "cancel-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        runs.cancel(tenant(), op.agentId(), fixture.runId()).orElseThrow();
        assertThatThrownBy(
                        () ->
                                journals.create(ORG_ID, fixture.runId())
                                        .prepare(invocation(fixture, "cancel")))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(database.consumedToolReplay(op.id())).isZero();
        assertThat(calls).hasValue(1);
        var current = operation(fixture);
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        administrator(),
                                        fixture.runId(),
                                        op.id(),
                                        "cancel-new-" + UUID.randomUUID(),
                                        evidence(current, Decision.CONFIRMED_NOT_PERFORMED, true)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .hasSize(1);

        var pending = createRun("cancel between prepare and dispatch");
        var call = invocation(pending, "prepared");
        var journal = journals.create(ORG_ID, pending.runId());
        assertThat(journal.prepare(call).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        runs.cancel(
                        tenant(),
                        resolutions.run(ORG_ID, pending.runId()).orElseThrow().agentId(),
                        pending.runId())
                .orElseThrow();
        assertThatThrownBy(() -> journal.markRunning(call))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(database.toolOperationState(pending.runId(), "prepared").status())
                .isEqualTo("CLAIMED");
    }

    @Test
    void sessionResetAndRunDeadlinePreventEvidenceOrReplay() {
        var fixture = createRun("session reset replay");
        invoke(
                toolkit(
                        new CountingTool(
                                new AtomicInteger(),
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null)),
                fixture,
                "reset",
                journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        assertThat(
                        database.updateRunDeadline(
                                fixture.runId(), OffsetDateTime.now().minusSeconds(1)))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        administrator(),
                                        fixture.runId(),
                                        op.id(),
                                        "deadline-" + UUID.randomUUID(),
                                        evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                journals.create(ORG_ID, fixture.runId())
                                        .prepare(invocation(fixture, "reset")))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(database.advanceSessionGeneration(fixture.sessionId())).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                reconciliations.ownedOperations(
                                        tenant(), op.agentId(), fixture.runId(), null, 25))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                reconciliations.history(
                                        administrator(), fixture.runId(), op.id(), 0, 25))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                journals.create(ORG_ID, fixture.runId())
                                        .prepare(invocation(fixture, "reset")))
                .isInstanceOf(ToolLeaseLostException.class);
    }

    @Test
    void concurrentDuplicateEvidenceIsAppendedOnceAndPaginationIsBounded() throws Exception {
        var fixture = createRun("concurrent evidence");
        invoke(
                toolkit(
                        new CountingTool(
                                new AtomicInteger(),
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null)),
                fixture,
                "concurrent-evidence",
                journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        var request = evidence(op, Decision.UNRESOLVED, false);
        String key = "concurrent-" + UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<ToolReconciliationService.Result> command =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        return reconciliations.reconcile(
                                administrator(), fixture.runId(), op.id(), key, request);
                    };
            var first = pool.submit(command);
            var second = pool.submit(command);
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.reused()).isNotEqualTo(b.reused());
            assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                    .hasSize(1);
            assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 1, 25))
                    .isEmpty();
            assertThat(
                            reconciliations
                                    .ownedOperations(
                                            tenant(), op.agentId(), fixture.runId(), op.id(), 1)
                                    .items())
                    .isEmpty();
            assertThatThrownBy(
                            () ->
                                    reconciliations.organizationOperations(
                                            administrator(), "ALL", null, 101))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_RECONCILIATION_TEST", matches = "true")
    void postgresRlsAndImmutablePrivilegesProtectEvidenceAndReplay() {
        var fixture = createRun("postgres evidence isolation");
        invoke(
                toolkit(
                        new CountingTool(
                                new AtomicInteger(),
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null)),
                fixture,
                "rls",
                journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "rls-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        var tenantDb =
                MyBatisRepositoryTestSupport.mapper(tenantDataSource, TestDatabaseMapper.class);
        assertThat(tenantDb.currentUser()).isEqualTo("app");
        TenantContextHolder.setOrgId(UUID.randomUUID().toString());
        try {
            assertThat(resolutions.run(ORG_ID, fixture.runId())).isEmpty();
            assertThat(resolutions.latest(ORG_ID, fixture.runId(), op.id())).isEmpty();
            assertThat(resolutions.history(ORG_ID, fixture.runId(), op.id(), 0, 25)).isEmpty();
        } finally {
            TenantContextHolder.setOrgId(ORG_ID.toString());
        }
        assertThatThrownBy(() -> tenantDb.rewriteToolResolution(op.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> tenantDb.deleteToolResolution(op.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> tenantDb.forgeToolReplayConsumption(op.id(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .hasSize(1);
        assertThat(database.consumedToolReplay(op.id())).isZero();
    }

    @Test
    void consumedReplayCannotTransferToANewInvocationAfterAClaimCrash() {
        var fixture = createRun("consumed approval does not transfer");
        var calls = new AtomicInteger();
        var tool =
                toolkit(
                        new CountingTool(
                                calls,
                                ToolRetrySafety.NEVER,
                                new IllegalStateException("unknown"),
                                null));
        invoke(tool, fixture, "claim-crash", journals.create(ORG_ID, fixture.runId()));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "claim-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        var original = invocation(fixture, "claim-crash");
        var journal = journals.create(ORG_ID, fixture.runId());
        assertThat(journal.prepare(original).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        assertThat(database.consumedToolReplay(op.id())).isEqualTo(1);
        assertThat(
                        journals.create(ORG_ID, fixture.runId())
                                .prepare(invocation(fixture, "claim-crash"))
                                .action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.RECONCILE);
        assertThatThrownBy(() -> journal.markRunning(original))
                .isInstanceOf(IllegalStateException.class);
        assertThat(database.toolOperationState(fixture.runId(), "claim-crash").status())
                .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(calls).hasValue(1);
        op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "fresh-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        assertThat(
                        text(
                                invoke(
                                        tool,
                                        fixture,
                                        "claim-crash",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("ok-2");
        assertThat(calls).hasValue(2);
        assertThat(reconciliations.history(administrator(), fixture.runId(), op.id(), 0, 25))
                .hasSize(2);
    }

    @Test
    void consumedReplayRechecksExpiryAndRoleImmediatelyBeforeDispatch() {
        for (boolean revokeRole : List.of(false, true)) {
            var fixture = createRun("replay dispatch revalidation");
            invoke(
                    toolkit(
                            new CountingTool(
                                    new AtomicInteger(),
                                    ToolRetrySafety.NEVER,
                                    new IllegalStateException("unknown"),
                                    null)),
                    fixture,
                    "dispatch-recheck",
                    journals.create(ORG_ID, fixture.runId()));
            var op = operation(fixture);
            reconciliations.reconcile(
                    administrator(),
                    fixture.runId(),
                    op.id(),
                    "dispatch-" + UUID.randomUUID(),
                    evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
            var invocation = invocation(fixture, "dispatch-recheck");
            var journal = journals.create(ORG_ID, fixture.runId());
            assertThat(journal.prepare(invocation).action())
                    .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
            var admin =
                    users.findByOrgIdAndId(ORG_ID, UUID.fromString(administrator().userId()))
                            .orElseThrow();
            String role = admin.getRole();
            try {
                if (revokeRole) {
                    admin.setRole("member");
                    users.save(admin);
                } else
                    assertThat(
                                    database.expireToolReplay(
                                            op.id(), OffsetDateTime.now().minusSeconds(1)))
                            .isEqualTo(1);
                assertThatThrownBy(() -> journal.markRunning(invocation))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Replay approval");
                assertThat(
                                database.toolOperationState(fixture.runId(), "dispatch-recheck")
                                        .status())
                        .isEqualTo("CLAIMED");
                assertThat(
                                journals.create(ORG_ID, fixture.runId())
                                        .prepare(invocation(fixture, "dispatch-recheck"))
                                        .action())
                        .isEqualTo(ToolExecutionJournal.PrepareAction.RECONCILE);
                assertThat(
                                database.toolOperationState(fixture.runId(), "dispatch-recheck")
                                        .status())
                        .isEqualTo("OUTCOME_UNKNOWN");
            } finally {
                admin.setRole(role);
                users.save(admin);
            }
        }
    }

    @Test
    void outdatedEvidenceCannotFallThroughToAutomaticSafeReplay() {
        var fixture = createRun("outdated safe replay evidence");
        var original = readOnlyInvocation(fixture, "safe-unknown");
        var journal = journals.create(ORG_ID, fixture.runId());
        assertThat(journal.prepare(original).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        journal.markRunning(original);
        journal.complete(
                original,
                ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN,
                null,
                new IllegalStateException("transport outcome unknown"));
        var op = operation(fixture);
        reconciliations.reconcile(
                administrator(),
                fixture.runId(),
                op.id(),
                "safe-" + UUID.randomUUID(),
                evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
        var replay = readOnlyInvocation(fixture, "safe-unknown");
        assertThat(journals.create(ORG_ID, fixture.runId()).prepare(replay).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        assertThat(database.expireToolReplay(op.id(), OffsetDateTime.now().minusSeconds(1)))
                .isEqualTo(1);
        assertThat(
                        journals.create(ORG_ID, fixture.runId())
                                .prepare(readOnlyInvocation(fixture, "safe-unknown"))
                                .action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.RECONCILE);
        assertThat(
                        journals.create(ORG_ID, fixture.runId())
                                .prepare(readOnlyInvocation(fixture, "safe-unknown"))
                                .action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.RECONCILE);
        assertThat(database.consumedToolReplay(op.id())).isEqualTo(1);
        assertThat(operation(fixture).status()).isEqualTo("OUTCOME_UNKNOWN");
    }

    private static ToolExecutionJournal.Invocation readOnlyInvocation(
            Fixture fixture, String call) {
        var original = invocation(fixture, call);
        return new ToolExecutionJournal.Invocation(
                original.operationId(),
                original.invocationId(),
                original.stepId(),
                original.identity(),
                original.executionLease(),
                original.toolCallId(),
                original.toolName(),
                original.inputHash(),
                original.input(),
                ToolRetrySafety.READ_ONLY,
                ToolContractSnapshot.capture(
                        new CountingTool(
                                new AtomicInteger(), ToolRetrySafety.READ_ONLY, null, null),
                        Map.of()),
                original.logicalOriginId());
    }

    private Fixture createRun(String title) {
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        database.insertAgent(agentId, ORG_ID, USER_ID, "tool-journal-" + agentId);
        database.insertChatSession(sessionId, ORG_ID, USER_ID, agentId, title);
        var handle = runs.createDirectRun(tenant(), agentId, sessionId, null, title);
        return new Fixture(
                handle.runId(),
                handle.rootTaskId(),
                handle.rootAgentRunId(),
                handle.rootAttemptId(),
                sessionId);
    }

    @Test
    void changedImplementationCannotReuseSuccessOrConsumeReplayApproval() {
        for (boolean failure : List.of(false, true)) {
            var fixture = createRun("changed tool implementation");
            var calls = new AtomicInteger();
            var tool =
                    new VersionedCountingTool(
                            calls, failure ? new IllegalStateException("unknown") : null);
            var kit = toolkit(tool);
            invoke(kit, fixture, "versioned", journals.create(ORG_ID, fixture.runId()));
            var op = operation(fixture);
            if (failure)
                reconciliations.reconcile(
                        administrator(),
                        fixture.runId(),
                        op.id(),
                        "contract-" + UUID.randomUUID(),
                        evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true));
            tool.version = "2";
            assertThat(
                            text(
                                    invoke(
                                            kit,
                                            fixture,
                                            "versioned",
                                            journals.create(ORG_ID, fixture.runId()))))
                    .contains("contract");
            assertThat(calls).hasValue(1);
            assertThat(database.consumedToolReplay(op.id())).isZero();
            assertThat(database.countCallerOperations(fixture.runId(), "versioned")).isEqualTo(1);
        }
    }

    @Test
    void legacyNamespaceAliasesVerifiedOriginWithoutDuplicatingTheOperation() {
        var fixture = createRun("verified old key alias");
        var input = invocation(fixture, "alias");
        var journal = journals.create(ORG_ID, fixture.runId());
        assertThat(journal.prepare(input).action())
                .isEqualTo(ToolExecutionJournal.PrepareAction.EXECUTE);
        journal.markRunning(input);
        journal.complete(
                input,
                ToolExecutionJournal.TerminalStatus.SUCCEEDED,
                ToolResultBlock.text("stored"),
                null);
        var calls = new AtomicInteger();
        assertThat(
                        text(
                                invoke(
                                        toolkit(
                                                new CountingTool(
                                                        calls, ToolRetrySafety.NEVER, null, null)),
                                        fixture,
                                        "alias",
                                        journals.create(ORG_ID, fixture.runId()))))
                .isEqualTo("stored");
        assertThat(calls).hasValue(0);
        assertThat(database.countCallerOperations(fixture.runId(), "alias")).isEqualTo(1);
        assertThat(database.toolOperationState(fixture.runId(), "alias").operationKey())
                .isEqualTo(input.operationId());
    }

    @Test
    void legacyMissingContractDoesNotCreateANewKeyAndBlindlyReplay() {
        var fixture = createRun("legacy unverified contract");
        UUID old = UUID.randomUUID();
        assertThat(
                        database.insertLegacyToolOperation(
                                old,
                                ORG_ID,
                                fixture.runId(),
                                fixture.taskId(),
                                fixture.agentRunId(),
                                fixture.attemptId(),
                                fixture.runId() + ":legacy-call",
                                UUID.randomUUID(),
                                "legacy-call",
                                StepSnapshot.fingerprint(Map.of("value", "alpha")),
                                io.agentscope.core.util.JsonUtils.getJsonCodec()
                                        .toJson(ToolResultBlock.text("old result"))))
                .isEqualTo(1);
        var calls = new AtomicInteger();
        assertThat(
                        text(
                                invoke(
                                        toolkit(
                                                new CountingTool(
                                                        calls, ToolRetrySafety.NEVER, null, null)),
                                        fixture,
                                        "legacy-call",
                                        journals.create(ORG_ID, fixture.runId()))))
                .contains("Legacy operation");
        assertThat(calls).hasValue(0);
        assertThat(database.countCallerOperations(fixture.runId(), "legacy-call")).isEqualTo(1);
        assertThat(database.toolOperationState(fixture.runId(), "legacy-call").id()).isEqualTo(old);
    }

    @Test
    void unversionedRemoteBehaviorCannotReuseOrApproveReplay() {
        var fixture = createRun("unversioned implementation");
        var calls = new AtomicInteger();
        var tool = new VersionedCountingTool(calls, null);
        tool.version = null;
        invoke(toolkit(tool), fixture, "opaque", journals.create(ORG_ID, fixture.runId()));
        assertThat(
                        text(
                                invoke(
                                        toolkit(tool),
                                        fixture,
                                        "opaque",
                                        journals.create(ORG_ID, fixture.runId()))))
                .contains("no verified implementation");
        assertThat(calls).hasValue(1);
        var failed = createRun("unversioned unknown behavior");
        var unknown =
                new VersionedCountingTool(
                        new AtomicInteger(), new IllegalStateException("unknown"));
        unknown.version = null;
        invoke(toolkit(unknown), failed, "opaque-unknown", journals.create(ORG_ID, failed.runId()));
        var op = operation(failed);
        assertThatThrownBy(
                        () ->
                                reconciliations.reconcile(
                                        administrator(),
                                        failed.runId(),
                                        op.id(),
                                        "opaque-" + UUID.randomUUID(),
                                        evidence(op, Decision.CONFIRMED_NOT_PERFORMED, true)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(reconciliations.history(administrator(), failed.runId(), op.id(), 0, 25))
                .isEmpty();
    }

    @Test
    void repeatedProviderCallIdsDistinguishModelMessagesAndChildAgents() {
        var root = createRun("repeated provider call IDs");
        var calls = new AtomicInteger();
        var kit = toolkit(new CountingTool(calls, ToolRetrySafety.NEVER, null, null));
        assertThat(
                        text(
                                invoke(
                                        kit,
                                        root,
                                        "call-0",
                                        journals.create(ORG_ID, root.runId()),
                                        "direct:" + root.runId(),
                                        "message-1")))
                .isEqualTo("ok-1");
        assertThat(
                        text(
                                invoke(
                                        kit,
                                        root,
                                        "call-0",
                                        journals.create(ORG_ID, root.runId()),
                                        "direct:" + root.runId(),
                                        "message-2")))
                .isEqualTo("ok-2");
        assertThat(
                        text(
                                invoke(
                                        kit,
                                        root,
                                        "call-0",
                                        journals.create(ORG_ID, root.runId()),
                                        "direct:" + root.runId(),
                                        "message-1")))
                .isEqualTo("ok-1");
        var source = resolutions.run(ORG_ID, root.runId()).orElseThrow();
        var task =
                runs.createSubagentTask(
                        tenant(),
                        source.agentId(),
                        root.runId(),
                        root.agentRunId(),
                        "child-" + UUID.randomUUID(),
                        "fixture",
                        "child-session",
                        "{}",
                        null);
        assertThat(database.markLeaseTaskRunning(task.taskId())).isEqualTo(1);
        assertThat(database.markAgentRunRunning(task.agentRunId())).isEqualTo(1);
        UUID attempt = UUID.randomUUID();
        String owner = "child-owner-" + attempt;
        var now = OffsetDateTime.now();
        assertThat(
                        database.insertRunningAttempt(
                                attempt,
                                ORG_ID,
                                root.runId(),
                                task.taskId(),
                                task.agentRunId(),
                                1,
                                owner,
                                now.plusMinutes(5),
                                owner,
                                now))
                .isEqualTo(1);
        var child =
                new Fixture(
                        root.runId(), task.taskId(), task.agentRunId(), attempt, root.sessionId());
        assertThat(
                        text(
                                invoke(
                                        kit,
                                        child,
                                        "call-0",
                                        journals.create(ORG_ID, root.runId()),
                                        owner,
                                        "message-1")))
                .isEqualTo("ok-3");
        assertThat(calls).hasValue(3);
        assertThat(database.countCallerOperations(root.runId(), "call-0")).isEqualTo(3);
    }

    private static ToolResultBlock invoke(
            Toolkit toolkit, Fixture fixture, String callId, ToolExecutionJournal journal) {
        return invoke(toolkit, fixture, callId, journal, "direct:" + fixture.runId());
    }

    private static ToolResultBlock invoke(
            Toolkit toolkit,
            Fixture fixture,
            String callId,
            ToolExecutionJournal journal,
            String leaseOwner) {
        return invoke(toolkit, fixture, callId, journal, leaseOwner, "step-1");
    }

    private static ToolResultBlock invoke(
            Toolkit toolkit,
            Fixture fixture,
            String callId,
            ToolExecutionJournal journal,
            String leaseOwner,
            String origin) {
        StepSnapshot.Identity identity =
                new StepSnapshot.Identity(
                        fixture.runId().toString(),
                        fixture.agentRunId().toString(),
                        fixture.taskId().toString(),
                        fixture.attemptId().toString());
        StepSnapshot step =
                new StepSnapshot(
                        "step-1",
                        1,
                        identity,
                        "integration-model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("durable_tool"));
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId(fixture.sessionId().toString())
                        .userId(USER_ID.toString())
                        .put(StepSnapshot.class, step)
                        .put(ExecutionLeaseSnapshot.class, new ExecutionLeaseSnapshot(leaseOwner))
                        .put(ToolExecutionJournal.class, journal)
                        .put(
                                io.agentscope.core.tool.ToolInvocationOrigins.class,
                                new io.agentscope.core.tool.ToolInvocationOrigins(
                                        Map.of(callId, origin)))
                        .build();
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id(callId)
                        .name("durable_tool")
                        .input(Map.of("value", "alpha"))
                        .content("{\"value\":\"alpha\"}")
                        .build();
        String previous = TenantContextHolder.getOrgId();
        try {
            return toolkit.callTools(List.of(use), null, null, context).block(TIMEOUT).get(0);
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }

    private static ToolExecutionJournal.Invocation invocation(Fixture fixture, String callId) {
        StepSnapshot.Identity identity = identity(fixture);
        return new ToolExecutionJournal.Invocation(
                fixture.runId() + ":" + callId,
                UUID.randomUUID().toString(),
                "step-1",
                identity,
                new ExecutionLeaseSnapshot("direct:" + fixture.runId()),
                callId,
                "durable_tool",
                StepSnapshot.fingerprint(Map.of("value", "alpha")),
                Map.of("value", "alpha"),
                ToolRetrySafety.NEVER,
                ToolContractSnapshot.capture(
                        new CountingTool(new AtomicInteger(), ToolRetrySafety.NEVER, null, null),
                        Map.of()));
    }

    private static StepSnapshot.Identity identity(Fixture fixture) {
        return new StepSnapshot.Identity(
                fixture.runId().toString(),
                fixture.agentRunId().toString(),
                fixture.taskId().toString(),
                fixture.attemptId().toString());
    }

    private static Toolkit toolkit(AgentTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private static TenantContext tenant() {
        return new TenantContext(
                ORG_ID.toString(), USER_ID.toString(), "member", "standard", 2, 100_000);
    }

    private record Fixture(
            UUID runId, UUID taskId, UUID agentRunId, UUID attemptId, UUID sessionId) {}

    private record Barriers(CountDownLatch entered, CountDownLatch release) {}

    private static final class VersionedCountingTool implements AgentTool {
        private final CountingTool delegate;
        String version = "1";

        VersionedCountingTool(AtomicInteger calls, RuntimeException failure) {
            delegate = new CountingTool(calls, ToolRetrySafety.NEVER, failure, null);
        }

        public String getName() {
            return delegate.getName();
        }

        public String getDescription() {
            return delegate.getDescription();
        }

        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }

        public String getContractVersion() {
            return version;
        }

        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return delegate.callAsync(param);
        }
    }

    private static final class CountingTool implements AgentTool {
        private final AtomicInteger calls;
        private final ToolRetrySafety retrySafety;
        private final RuntimeException failure;
        private final Barriers barriers;

        private CountingTool(
                AtomicInteger calls,
                ToolRetrySafety retrySafety,
                RuntimeException failure,
                Barriers barriers) {
            this.calls = calls;
            this.retrySafety = retrySafety;
            this.failure = failure;
            this.barriers = barriers;
        }

        @Override
        public String getName() {
            return "durable_tool";
        }

        @Override
        public ToolRetrySafety getRetrySafety() {
            return retrySafety;
        }

        @Override
        public String getDescription() {
            return "durable journal integration tool";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object", "properties", Map.of("value", Map.of("type", "string")));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(
                    () -> {
                        int call = calls.incrementAndGet();
                        if (barriers != null) {
                            barriers.entered().countDown();
                            if (!barriers.release().await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("tool release barrier timed out");
                            }
                        }
                        if (failure != null && call == 1) throw failure;
                        return ToolResultBlock.text("ok-" + call);
                    });
        }
    }
}
