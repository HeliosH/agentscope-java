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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.ModelException;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService.TaskLease;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.DurableTaskExecutor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DurableTaskWorkerTest {

    private DurableTaskLeaseService leases;
    private DurableTaskExecutor executor;
    private DurableTaskWorker worker;
    private TaskLease lease;

    @BeforeEach
    void setUp() {
        leases = mock(DurableTaskLeaseService.class);
        executor = mock(DurableTaskExecutor.class);
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setSchedulerEnabled(true);
        properties.getOrchestration().setWorkerConcurrency(2);
        lease = lease();
        worker = new DurableTaskWorker(leases, executor, properties, Runnable::run, "worker-test");
        when(leases.claimReady("worker-test", 2)).thenReturn(List.of(lease));
        when(leases.start(lease.attemptId(), "worker-test")).thenReturn(true);
    }

    @Test
    void executesClaimAndPersistsStructuredResult() throws Exception {
        when(executor.execute(any()))
                .thenReturn(new DurableTaskExecutor.ExecutionResult("{\"summary\":\"done\"}"));
        when(leases.succeed(lease.attemptId(), "worker-test", "{\"summary\":\"done\"}"))
                .thenReturn(true);

        assertThat(worker.pollOnce()).isEqualTo(1);

        verify(leases).start(lease.attemptId(), "worker-test");
        ArgumentCaptor<DurableTaskExecutor.ExecutionRequest> request =
                ArgumentCaptor.forClass(DurableTaskExecutor.ExecutionRequest.class);
        verify(executor).execute(request.capture());
        assertThat(request.getValue().workspaceIsolationMode())
                .isEqualTo(WorkspaceIsolationMode.ATTEMPT_ISOLATED);
        verify(leases).succeed(lease.attemptId(), "worker-test", "{\"summary\":\"done\"}");
    }

    @Test
    void nonModelExecutionFailureIsTerminalRatherThanRetried() throws Exception {
        when(executor.execute(any())).thenThrow(new IllegalStateException("model unavailable"));

        assertThat(worker.pollOnce()).isEqualTo(1);

        verify(leases)
                .failTerminal(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("TASK_EXECUTION_FAILED"),
                        eq("model unavailable"));
    }

    @Test
    void modelFailureUsesDurableRecoveryPolicy() throws Exception {
        when(executor.execute(any())).thenThrow(new ModelException("gateway interrupted"));

        assertThat(worker.pollOnce()).isEqualTo(1);

        verify(leases)
                .recover(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("MODEL_REQUEST_FAILED"),
                        eq("gateway interrupted"),
                        org.mockito.ArgumentMatchers.longThat(
                                delay -> delay >= 750L && delay <= 1250L));
    }

    @Test
    void archiveHandoverReleasesWorkerWithoutBusinessSuccessOrRetry() throws Exception {
        when(executor.execute(any()))
                .thenThrow(
                        new io.agentscope.saas.app.workspace.WorkspaceArchiveDeferredException(
                                UUID.randomUUID()));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases, never()).succeed(any(), any(), any());
        verify(leases, never()).fail(any(), any(), any(), any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        when(leases.claimReady("worker-test", 2)).thenReturn(List.of());
        assertThat(worker.pollOnce()).isZero();
    }

    @Test
    void unsealedComputeResultCannotTriggerTaskReplayEvenWithAModelErrorCause() throws Exception {
        when(executor.execute(any()))
                .thenThrow(
                        new io.agentscope.saas.app.workspace.WorkspaceArchiveRejectedException(
                                new ModelException("nested transport cause")));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases)
                .failTerminal(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("WORKSPACE_ARCHIVE_HANDOVER_FAILED"),
                        any());
        verify(leases, never()).fail(any(), any(), any(), any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void statusSubmissionFailureHandsOverCapturedResultWithoutRunningExecutorAgain()
            throws Exception {
        var job = UUID.randomUUID();
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult(
                                "{}",
                                error -> {
                                    throw new io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveDeferredException(job);
                                }));
        when(leases.succeed(any(), any(), any()))
                .thenThrow(new ModelException("status acknowledgement lost"));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(executor).execute(any());
        verify(leases, never()).fail(any(), any(), any(), any());
        verify(leases, never()).failTerminal(any(), any(), any(), any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void legacyResultWithoutACommitHandlerStopsInsteadOfReplayingCompletedCompute()
            throws Exception {
        when(executor.execute(any())).thenReturn(new DurableTaskExecutor.ExecutionResult("{}"));
        when(leases.succeed(any(), any(), any()))
                .thenThrow(new ModelException("commit transport failed"));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases)
                .failTerminal(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("TASK_RESULT_COMMIT_FAILED"),
                        any());
        verify(leases, never()).fail(any(), any(), any(), any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void lostAcknowledgementOfAnAlreadyCommittedAttemptDoesNotCreateAnArchiveJob()
            throws Exception {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult("{}", error -> called.set(true)));
        when(leases.succeed(any(), any(), any())).thenThrow(new IllegalStateException("ack lost"));
        when(leases.completedSuccessfully(lease.attemptId(), "worker-test")).thenReturn(true);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(called).isFalse();
        verify(executor).execute(any());
        verify(leases, never()).failTerminal(any(), any(), any(), any());
    }

    @Test
    void failedCommitStateReadStillCannotTurnSubmissionIntoAModelRetry() throws Exception {
        var error = new ModelException("commit lost");
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult(
                                "{}",
                                cause -> {
                                    assertThat(cause.getSuppressed()).hasSize(1);
                                    throw new io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveDeferredException(UUID.randomUUID());
                                }));
        when(leases.succeed(any(), any(), any())).thenThrow(error);
        when(leases.completedSuccessfully(any(), any()))
                .thenThrow(new IllegalStateException("status read unavailable"));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(leases, never()).failTerminal(any(), any(), any(), any());
    }

    @Test
    void arbitraryHandlerModelFailureRemainsACommitFailure() throws Exception {
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult(
                                "{}",
                                error -> {
                                    throw new ModelException("handler nested model cause");
                                }));
        when(leases.succeed(any(), any(), any()))
                .thenThrow(new IllegalStateException("status write unavailable"));
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases)
                .failTerminal(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("TASK_RESULT_COMMIT_FAILED"),
                        any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void explicitLeaseLossDoesNotInvokeTheObsoleteResultsHandler() throws Exception {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult("{}", error -> called.set(true)));
        when(leases.succeed(any(), any(), any())).thenReturn(false);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(called).isFalse();
        verify(leases, never()).failTerminal(any(), any(), any(), any());
    }

    @Test
    void emptyExecutorReturnIsNotPermissionToReplayItsPossibleSideEffects() throws Exception {
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases)
                .failTerminal(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("TASK_RESULT_COMMIT_FAILED"),
                        any());
        verify(leases, never()).fail(any(), any(), any(), any());
    }

    @Test
    void completionCommittedWhileTheHandoverWasAttemptedDoesNotBecomeATerminalFailure()
            throws Exception {
        when(executor.execute(any()))
                .thenReturn(
                        new DurableTaskExecutor.ExecutionResult(
                                "{}",
                                error -> {
                                    throw new io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveRejectedException(error);
                                }));
        when(leases.succeed(any(), any(), any())).thenThrow(new IllegalStateException("ack raced"));
        when(leases.completedSuccessfully(lease.attemptId(), "worker-test"))
                .thenReturn(false, true);
        assertThat(worker.pollOnce()).isEqualTo(1);
        verify(leases, never()).failTerminal(any(), any(), any(), any());
        verify(leases, never())
                .recover(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(executor).execute(any());
    }

    private static TaskLease lease() {
        return new TaskLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "assistant",
                null,
                "member",
                "standard",
                2,
                100_000,
                UUID.randomUUID(),
                1,
                "worker-test",
                OffsetDateTime.now().plusMinutes(1),
                "Generate report",
                "{\"prompt\":\"Generate report\"}",
                WorkspaceIsolationMode.ATTEMPT_ISOLATED);
    }
}
