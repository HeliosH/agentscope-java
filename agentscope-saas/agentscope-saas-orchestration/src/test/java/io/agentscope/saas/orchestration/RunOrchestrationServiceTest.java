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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.AgentRunEntity;
import io.agentscope.saas.core.persistence.entity.AssistantRunEntity;
import io.agentscope.saas.core.persistence.entity.OrchestrationOutboxEntity;
import io.agentscope.saas.core.persistence.entity.RunAttemptEntity;
import io.agentscope.saas.core.persistence.entity.RunEventEntity;
import io.agentscope.saas.core.persistence.entity.TaskNodeEntity;
import io.agentscope.saas.core.persistence.repo.AgentRunRepository;
import io.agentscope.saas.core.persistence.repo.AssistantRunRepository;
import io.agentscope.saas.core.persistence.repo.OrchestrationOutboxRepository;
import io.agentscope.saas.core.persistence.repo.RunAttemptRepository;
import io.agentscope.saas.core.persistence.repo.RunEventRepository;
import io.agentscope.saas.core.persistence.repo.TaskNodeRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunOrchestrationServiceTest {

    private final AssistantRunRepository runRepository = mock(AssistantRunRepository.class);
    private final TaskNodeRepository taskRepository = mock(TaskNodeRepository.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final RunAttemptRepository attemptRepository = mock(RunAttemptRepository.class);
    private final RunEventRepository eventRepository = mock(RunEventRepository.class);
    private final OrchestrationOutboxRepository outboxRepository =
            mock(OrchestrationOutboxRepository.class);
    private final RunOrchestrationService service =
            new RunOrchestrationService(
                    runRepository,
                    taskRepository,
                    agentRunRepository,
                    attemptRepository,
                    eventRepository,
                    outboxRepository);

    @Test
    void createsDirectRunWithRootTaskAndOrderedEvents() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runRepository.save(any(AssistantRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RunOrchestrationService.RunHandle handle =
                service.createDirectRun(
                        tenant(orgId, userId),
                        agentId,
                        sessionId,
                        UUID.randomUUID(),
                        "Build a report");

        ArgumentCaptor<AssistantRunEntity> runCaptor =
                ArgumentCaptor.forClass(AssistantRunEntity.class);
        ArgumentCaptor<TaskNodeEntity> taskCaptor = ArgumentCaptor.forClass(TaskNodeEntity.class);
        ArgumentCaptor<AgentRunEntity> agentRunCaptor =
                ArgumentCaptor.forClass(AgentRunEntity.class);
        ArgumentCaptor<RunAttemptEntity> attemptCaptor =
                ArgumentCaptor.forClass(RunAttemptEntity.class);
        ArgumentCaptor<RunEventEntity> eventCaptor = ArgumentCaptor.forClass(RunEventEntity.class);
        ArgumentCaptor<OrchestrationOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(OrchestrationOutboxEntity.class);
        verify(runRepository).save(runCaptor.capture());
        verify(taskRepository).save(taskCaptor.capture());
        verify(agentRunRepository).save(agentRunCaptor.capture());
        verify(attemptRepository).save(attemptCaptor.capture());
        verify(eventRepository, org.mockito.Mockito.times(3)).save(eventCaptor.capture());
        verify(outboxRepository, org.mockito.Mockito.times(3)).save(outboxCaptor.capture());

        AssistantRunEntity run = runCaptor.getValue();
        TaskNodeEntity task = taskCaptor.getValue();
        assertThat(handle.runId()).isEqualTo(run.getId());
        assertThat(run.getStatus()).isEqualTo(RunOrchestrationService.RUN_RUNNING);
        assertThat(task.getRunId()).isEqualTo(handle.runId());
        assertThat(task.getStatus()).isEqualTo(RunOrchestrationService.TASK_RUNNING);
        assertThat(task.getWorkspaceMode()).isEqualTo("NONE");
        assertThat(agentRunCaptor.getValue().getTaskId()).isEqualTo(task.getId());
        assertThat(agentRunCaptor.getValue().getStatus())
                .isEqualTo(RunOrchestrationService.RUN_RUNNING);
        assertThat(attemptCaptor.getValue().getAgentRunId())
                .isEqualTo(agentRunCaptor.getValue().getId());
        assertThat(attemptCaptor.getValue().getStatus())
                .isEqualTo(RunOrchestrationService.ATTEMPT_RUNNING);
        assertThat(eventCaptor.getAllValues())
                .extracting(RunEventEntity::getSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(eventCaptor.getAllValues())
                .extracting(RunEventEntity::getEventType)
                .containsExactly("RUN_CREATED", "RUN_STARTED", "TASK_STARTED");
        assertThat(run.getNextEventSeq()).isEqualTo(3);
        assertThat(outboxCaptor.getAllValues())
                .extracting(OrchestrationOutboxEntity::getEventType)
                .containsExactly("RUN_CREATED", "RUN_STARTED", "TASK_STARTED");
        assertThat(outboxCaptor.getAllValues())
                .allSatisfy(
                        outbox -> {
                            assertThat(outbox.getAggregateId()).isEqualTo(run.getId());
                            assertThat(outbox.getPayloadJson()).contains("\"seq\"");
                        });
    }

    @Test
    void explicitCancelTransitionsOnlyTheOwnedRunningRun() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AssistantRunEntity run = runningRun(runId, orgId, userId, agentId, sessionId);
        TaskNodeEntity root = runningTask(runId, orgId);
        AgentRunEntity agentRun = runningAgentRun(runId, root.getId(), orgId);
        RunAttemptEntity attempt = runningAttempt(runId, root.getId(), agentRun.getId(), orgId);
        when(runRepository.lockOwnedRun(runId, orgId, userId, agentId))
                .thenReturn(Optional.of(run));
        when(taskRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(runId, orgId))
                .thenReturn(List.of(root));
        when(agentRunRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(runId, orgId))
                .thenReturn(List.of(agentRun));
        when(attemptRepository.findByRunIdAndOrgIdOrderByAttemptNoAsc(runId, orgId))
                .thenReturn(List.of(attempt));
        Optional<RunOrchestrationService.CancelledRun> cancelled =
                service.cancel(tenant(orgId, userId), agentId, runId);

        assertThat(cancelled).isPresent();
        assertThat(cancelled.orElseThrow().interrupted()).isTrue();
        assertThat(run.getStatus()).isEqualTo(RunOrchestrationService.RUN_CANCELLED);
        assertThat(run.isCancelRequested()).isTrue();
        assertThat(root.getStatus()).isEqualTo(RunOrchestrationService.TASK_CANCELLED);
        assertThat(agentRun.getStatus()).isEqualTo(RunOrchestrationService.TASK_CANCELLED);
        assertThat(attempt.getStatus()).isEqualTo(RunOrchestrationService.ATTEMPT_CANCELLED);
        verify(taskRepository).save(root);
        verify(agentRunRepository).save(agentRun);
        verify(attemptRepository).save(attempt);
    }

    @Test
    void reusesRunForTheSameIdempotencyKey() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AssistantRunEntity existing = runningRun(runId, orgId, userId, agentId, sessionId);
        existing.setIdempotencyKey("request-1");
        when(runRepository.findByOrgIdAndUserIdAndAgentIdAndIdempotencyKey(
                        orgId, userId, agentId, "request-1"))
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
        verify(taskRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    private static TenantContext tenant(UUID orgId, UUID userId) {
        return new TenantContext(
                orgId.toString(), userId.toString(), "member", "standard", 2, 10_000);
    }

    private static AssistantRunEntity runningRun(
            UUID id, UUID orgId, UUID userId, UUID agentId, UUID sessionId) {
        AssistantRunEntity run = new AssistantRunEntity();
        run.setId(id);
        run.setOrgId(orgId);
        run.setUserId(userId);
        run.setAgentId(agentId);
        run.setSessionId(sessionId);
        run.setMode(RunOrchestrationService.MODE_DIRECT);
        run.setStatus(RunOrchestrationService.RUN_RUNNING);
        run.setNextEventSeq(0);
        return run;
    }

    private static TaskNodeEntity runningTask(UUID runId, UUID orgId) {
        TaskNodeEntity task = new TaskNodeEntity();
        task.setId(UUID.randomUUID());
        task.setRunId(runId);
        task.setOrgId(orgId);
        task.setStatus(RunOrchestrationService.TASK_RUNNING);
        return task;
    }

    private static AgentRunEntity runningAgentRun(UUID runId, UUID taskId, UUID orgId) {
        AgentRunEntity agentRun = new AgentRunEntity();
        agentRun.setId(UUID.randomUUID());
        agentRun.setRunId(runId);
        agentRun.setTaskId(taskId);
        agentRun.setOrgId(orgId);
        agentRun.setStatus(RunOrchestrationService.RUN_RUNNING);
        return agentRun;
    }

    private static RunAttemptEntity runningAttempt(
            UUID runId, UUID taskId, UUID agentRunId, UUID orgId) {
        RunAttemptEntity attempt = new RunAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setRunId(runId);
        attempt.setTaskId(taskId);
        attempt.setAgentRunId(agentRunId);
        attempt.setOrgId(orgId);
        attempt.setStatus(RunOrchestrationService.ATTEMPT_RUNNING);
        return attempt;
    }
}
