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
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.tool.ToolRetrySafety;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
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

    private final TestDatabaseMapper database;

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
                        .build();
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id(callId)
                        .name("durable_tool")
                        .input(Map.of("value", "alpha"))
                        .content("{\"value\":\"alpha\"}")
                        .build();
        return toolkit.callTools(List.of(use), null, null, context).block(TIMEOUT).get(0);
    }

    private static ToolExecutionJournal.Invocation invocation(Fixture fixture, String callId) {
        StepSnapshot.Identity identity = identity(fixture);
        return new ToolExecutionJournal.Invocation(
                fixture.runId() + ":" + callId,
                UUID.randomUUID().toString(),
                "step-duplicate",
                identity,
                new ExecutionLeaseSnapshot("direct:" + fixture.runId()),
                callId,
                "durable_tool",
                StepSnapshot.fingerprint(Map.of("value", "alpha")),
                Map.of("value", "alpha"),
                ToolRetrySafety.NEVER);
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
