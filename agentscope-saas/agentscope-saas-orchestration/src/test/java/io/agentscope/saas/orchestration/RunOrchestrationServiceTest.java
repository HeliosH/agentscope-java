/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AssistantRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewTask;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.RunAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.TaskNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunOrchestrationServiceTest {

    private final RunOrchestrationRepository repository = mock(RunOrchestrationRepository.class);
    private final RunOrchestrationService service = new RunOrchestrationService(repository);

    @Test
    void createsDirectRunWithRootTaskAndOrderedEvents() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AtomicReference<NewRun> insertedRun = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            insertedRun.set(invocation.getArgument(0));
                            return null;
                        })
                .when(repository)
                .insertRun(any(NewRun.class));
        when(repository.findOwnedRun(any(), eq(orgId), eq(userId), eq(agentId)))
                .thenAnswer(
                        invocation -> {
                            NewRun run = insertedRun.get();
                            return Optional.of(
                                    runningRun(run.id(), orgId, userId, agentId, sessionId));
                        });
        AtomicLong sequence = new AtomicLong();
        when(repository.nextEventSequence(any(), eq(orgId), any()))
                .thenAnswer(invocation -> sequence.incrementAndGet());

        RunOrchestrationService.RunHandle handle =
                service.createDirectRun(
                        tenant(orgId, userId),
                        agentId,
                        sessionId,
                        UUID.randomUUID(),
                        "Build a report");

        ArgumentCaptor<NewRun> runCaptor = ArgumentCaptor.forClass(NewRun.class);
        ArgumentCaptor<NewTask> taskCaptor = ArgumentCaptor.forClass(NewTask.class);
        ArgumentCaptor<NewAgentRun> agentRunCaptor = ArgumentCaptor.forClass(NewAgentRun.class);
        ArgumentCaptor<NewAttempt> attemptCaptor = ArgumentCaptor.forClass(NewAttempt.class);
        ArgumentCaptor<NewEvent> eventCaptor = ArgumentCaptor.forClass(NewEvent.class);
        ArgumentCaptor<NewOutboxMessage> outboxCaptor =
                ArgumentCaptor.forClass(NewOutboxMessage.class);
        verify(repository).insertRun(runCaptor.capture());
        verify(repository).insertTask(taskCaptor.capture());
        verify(repository).insertAgentRun(agentRunCaptor.capture());
        verify(repository).insertAttempt(attemptCaptor.capture());
        verify(repository, org.mockito.Mockito.times(4)).insertEvent(eventCaptor.capture());
        verify(repository, org.mockito.Mockito.times(4)).insertOutbox(outboxCaptor.capture());

        NewRun run = runCaptor.getValue();
        NewTask task = taskCaptor.getValue();
        assertThat(handle.runId()).isEqualTo(run.id());
        assertThat(run.status()).isEqualTo(RunOrchestrationService.RUN_RUNNING);
        assertThat(task.runId()).isEqualTo(handle.runId());
        assertThat(task.status()).isEqualTo(RunOrchestrationService.TASK_RUNNING);
        assertThat(task.workspaceMode()).isEqualTo("NONE");
        assertThat(agentRunCaptor.getValue().taskId()).isEqualTo(task.id());
        assertThat(agentRunCaptor.getValue().status())
                .isEqualTo(RunOrchestrationService.RUN_RUNNING);
        assertThat(attemptCaptor.getValue().agentRunId()).isEqualTo(agentRunCaptor.getValue().id());
        assertThat(attemptCaptor.getValue().status())
                .isEqualTo(RunOrchestrationService.ATTEMPT_RUNNING);
        assertThat(eventCaptor.getAllValues())
                .extracting(NewEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(eventCaptor.getAllValues())
                .extracting(NewEvent::eventType)
                .containsExactly(
                        "RUN_CREATED", "RUN_STARTED", "TASK_STARTED", "AGENT_PERMISSION_SNAPSHOT");
        assertThat(outboxCaptor.getAllValues())
                .extracting(NewOutboxMessage::eventType)
                .containsExactly(
                        "RUN_CREATED", "RUN_STARTED", "TASK_STARTED", "AGENT_PERMISSION_SNAPSHOT");
        assertThat(outboxCaptor.getAllValues())
                .allSatisfy(
                        outbox -> {
                            assertThat(outbox.aggregateId()).isEqualTo(run.id());
                            assertThat(outbox.payloadJson()).contains("\"seq\"");
                        });
    }

    @Test
    void explicitCancelTransitionsOnlyTheOwnedRunningRun() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AssistantRun run = runningRun(runId, orgId, userId, agentId, sessionId);
        TaskNode root = runningTask(runId, orgId);
        AgentRun agentRun = runningAgentRun(runId, root.id(), orgId);
        RunAttempt attempt = runningAttempt(runId, root.id(), agentRun.id(), orgId);
        when(repository.lockOwnedRun(runId, orgId, userId, agentId)).thenReturn(Optional.of(run));
        when(repository.findTasks(runId, orgId)).thenReturn(List.of(root));
        when(repository.findAgentRuns(runId, orgId)).thenReturn(List.of(agentRun));
        when(repository.findAttempts(runId, orgId)).thenReturn(List.of(attempt));
        when(repository.nextEventSequence(eq(runId), eq(orgId), any())).thenReturn(1L, 2L);

        Optional<RunOrchestrationService.CancelledRun> cancelled =
                service.cancel(tenant(orgId, userId), agentId, runId);

        assertThat(cancelled).isPresent();
        assertThat(cancelled.orElseThrow().interrupted()).isTrue();
        verify(repository)
                .completeRun(
                        eq(runId),
                        eq(orgId),
                        eq(RunOrchestrationService.RUN_CANCELLED),
                        eq(true),
                        eq(null),
                        eq(null),
                        any(),
                        any());
        verify(repository)
                .completeTask(
                        eq(root.id()),
                        eq(orgId),
                        eq(RunOrchestrationService.TASK_CANCELLED),
                        eq(null),
                        eq(null),
                        any(),
                        any());
        verify(repository)
                .updateAgentRunStatus(
                        eq(agentRun.id()),
                        eq(orgId),
                        eq(RunOrchestrationService.TASK_CANCELLED),
                        any(),
                        any());
        verify(repository)
                .updateAttemptStatus(
                        eq(attempt.id()),
                        eq(orgId),
                        eq(RunOrchestrationService.ATTEMPT_CANCELLED),
                        eq(null),
                        eq(null),
                        any(),
                        any());
    }

    @Test
    void reusesRunForTheSameIdempotencyKey() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AssistantRun existing = runningRun(runId, orgId, userId, agentId, sessionId);
        when(repository.findByIdempotencyKey(orgId, userId, agentId, "request-1"))
                .thenReturn(Optional.of(existing));

        RunOrchestrationService.RunHandle handle =
                service.createDirectRun(
                        tenant(orgId, userId),
                        agentId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "duplicate",
                        " request-1 ");

        assertThat(handle.runId()).isEqualTo(runId);
        assertThat(handle.sessionId()).isEqualTo(sessionId);
        assertThat(handle.reused()).isTrue();
        verify(repository, never()).insertTask(any());
        verify(repository, never()).insertEvent(any());
    }

    private static TenantContext tenant(UUID orgId, UUID userId) {
        return new TenantContext(
                orgId.toString(), userId.toString(), "member", "standard", 2, 10_000);
    }

    private static AssistantRun runningRun(
            UUID id, UUID orgId, UUID userId, UUID agentId, UUID sessionId) {
        return new AssistantRun(
                id,
                orgId,
                userId,
                agentId,
                sessionId,
                RunOrchestrationService.MODE_DIRECT,
                RunOrchestrationService.RUN_RUNNING,
                false,
                null,
                null,
                null,
                0,
                null,
                0,
                null,
                0,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
    }

    private static TaskNode runningTask(UUID runId, UUID orgId) {
        return new TaskNode(
                UUID.randomUUID(),
                orgId,
                runId,
                null,
                null,
                null,
                "root",
                "agent",
                RunOrchestrationService.TASK_RUNNING,
                "{}",
                "NONE",
                3,
                null,
                0,
                null,
                0,
                null,
                0,
                null,
                OffsetDateTime.now(),
                null);
    }

    private static AgentRun runningAgentRun(UUID runId, UUID taskId, UUID orgId) {
        return new AgentRun(
                UUID.randomUUID(),
                orgId,
                runId,
                taskId,
                null,
                "assistant",
                RunOrchestrationService.RUN_RUNNING,
                0,
                "{}",
                null);
    }

    private static RunAttempt runningAttempt(UUID runId, UUID taskId, UUID agentRunId, UUID orgId) {
        return new RunAttempt(
                UUID.randomUUID(),
                orgId,
                runId,
                taskId,
                agentRunId,
                1,
                RunOrchestrationService.ATTEMPT_RUNNING,
                null,
                null,
                OffsetDateTime.now(),
                null);
    }
}
