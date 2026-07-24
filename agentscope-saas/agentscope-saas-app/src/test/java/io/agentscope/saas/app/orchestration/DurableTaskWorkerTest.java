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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService.TaskLease;
import io.agentscope.saas.orchestration.DurableTaskExecutor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        verify(executor).execute(any());
        verify(leases).succeed(lease.attemptId(), "worker-test", "{\"summary\":\"done\"}");
    }

    @Test
    void executionFailureIsPersistedForRetryPolicy() throws Exception {
        when(executor.execute(any())).thenThrow(new IllegalStateException("model unavailable"));

        assertThat(worker.pollOnce()).isEqualTo(1);

        verify(leases)
                .fail(
                        eq(lease.attemptId()),
                        eq("worker-test"),
                        eq("TASK_EXECUTION_FAILED"),
                        eq("model unavailable"));
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
                "{\"prompt\":\"Generate report\"}");
    }
}
