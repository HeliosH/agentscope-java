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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.SandboxIsolationOverride;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.config.TenantRlsWebFilter;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class HarnessDurableTaskExecutorTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "reply,true",
        "reply,false",
        "status,true",
        "status,false"
    })
    void replyAndStatusSubmissionFailuresPreserveComputedAnswerWithoutCallingAgentAgain(
            String stage, boolean admitted) throws Exception {
        var parent = mock(HarnessAgent.class);
        var reply =
                Msg.builder().role(MsgRole.ASSISTANT).textContent("computed final answer").build();
        when(parent.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        var properties = new SaasProperties();
        properties.getSandbox().setEnabled(true);
        var chats = mock(ChatPersistenceService.class);
        var orchestration = mock(RunOrchestrationService.class);
        var artifacts = mock(WorkspaceArtifactService.class);
        when(artifacts.publish(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WorkspaceArtifactService.Publication(
                                "workspace-catalog://test", "version", 1));
        var restore = mock(WorkspaceCheckpointRestoreService.class);
        when(restore.prepare(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        var archives = mock(io.agentscope.saas.app.workspace.WorkspaceArchiveService.class);
        if (admitted)
            when(archives.defer(any(), any(), anyList(), eq(true))).thenReturn(UUID.randomUUID());
        else
            when(archives.defer(any(), any(), anyList(), eq(true)))
                    .thenThrow(new IllegalStateException("handover unavailable"));
        var executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chats,
                        orchestration,
                        artifacts,
                        restore,
                        Optional.empty(),
                        archives);
        if (stage.equals("reply")) {
            doThrow(
                            new io.agentscope.core.model.ModelException(
                                    "reply persistence transport failed"))
                    .when(chats)
                    .saveAssistantMessageForRun(any(), any(), any(), any(), anyList());
            assertThatThrownBy(() -> executor.execute(computedRequest()))
                    .isInstanceOf(
                            admitted
                                    ? io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveDeferredException.class
                                    : io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveRejectedException.class);
        } else {
            var result = executor.execute(computedRequest());
            assertThatThrownBy(
                            () ->
                                    result.commitFailed(
                                            new io.agentscope.core.model.ModelException(
                                                    "status transport failed")))
                    .isInstanceOf(io.agentscope.saas.orchestration.TaskResultCommitException.class)
                    .hasCauseInstanceOf(
                            admitted
                                    ? io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveDeferredException.class
                                    : io.agentscope.saas.app.workspace
                                            .WorkspaceArchiveRejectedException.class);
        }
        var pending = ArgumentCaptor.forClass(String.class);
        verify(archives).defer(any(), pending.capture(), eq(reply.getContent()), eq(true));
        assertThat(pending.getValue()).contains("computed final answer", "agent_result");
        verify(parent).call(any(Msg.class), any(RuntimeContext.class));
        verify(artifacts).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void postComputeDeliveryScopeLookupFailureIsNotAnExecutionFailure() throws Exception {
        var parent = mock(HarnessAgent.class);
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build()));
        var orchestration = mock(RunOrchestrationService.class);
        when(orchestration.hasUnsettledChildren(any()))
                .thenThrow(new io.agentscope.core.model.ModelException("metadata unavailable"));
        var artifacts = mock(WorkspaceArtifactService.class);
        var executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        new SaasProperties(),
                        mock(ChatPersistenceService.class),
                        orchestration,
                        artifacts,
                        mock(WorkspaceCheckpointRestoreService.class),
                        Optional.empty());
        assertThatThrownBy(() -> executor.execute(computedRequest()))
                .isInstanceOf(io.agentscope.saas.orchestration.TaskResultCommitException.class);
        verify(artifacts, never()).publish(any(), any(), any(), any(), any(), any());
        verify(parent).call(any(Msg.class), any(RuntimeContext.class));
    }

    private static ExecutionRequest computedRequest() {
        return new ExecutionRequest(
                UUID.randomUUID(),
                "worker-test",
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
                1,
                100,
                "compute",
                "{\"prompt\":\"compute\",\"_runtime\":{\"directChat\":true}}",
                WorkspaceIsolationMode.ATTEMPT_ISOLATED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void completedComputeIsHandedOverOrStoppedButNeverRepeated(boolean admitted) throws Exception {
        var parent = mock(HarnessAgent.class);
        var reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("finished compute").build();
        when(parent.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        var properties = new SaasProperties();
        properties.getSandbox().setEnabled(true);
        var chats = mock(ChatPersistenceService.class);
        var orchestration = mock(RunOrchestrationService.class);
        var artifacts = mock(WorkspaceArtifactService.class);
        doThrow(new IllegalStateException("catalog incomplete"))
                .when(artifacts)
                .publish(any(), any(), any(), any(), any(), any());
        var restore = mock(WorkspaceCheckpointRestoreService.class);
        when(restore.prepare(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        var archives = mock(io.agentscope.saas.app.workspace.WorkspaceArchiveService.class);
        if (admitted)
            when(archives.defer(any(), any(), anyList(), eq(true))).thenReturn(UUID.randomUUID());
        else
            when(archives.defer(any(), any(), anyList(), eq(true)))
                    .thenThrow(new IllegalStateException("bytes missing"));
        var executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chats,
                        orchestration,
                        artifacts,
                        restore,
                        Optional.empty(),
                        archives);
        var request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
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
                        1,
                        100,
                        "compute",
                        "{\"prompt\":\"compute\",\"_runtime\":{\"directChat\":true}}",
                        WorkspaceIsolationMode.ATTEMPT_ISOLATED);
        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(
                        admitted
                                ? io.agentscope.saas.app.workspace.WorkspaceArchiveDeferredException
                                        .class
                                : io.agentscope.saas.app.workspace.WorkspaceArchiveRejectedException
                                        .class);
        var output = ArgumentCaptor.forClass(String.class);
        verify(archives).defer(any(), output.capture(), eq(reply.getContent()), eq(true));
        assertThat(output.getValue()).contains("finished compute", "agent_result");
        verify(parent).call(any(Msg.class), any(RuntimeContext.class));
        verify(chats, never()).saveAssistantMessageForRun(any(), any(), any(), any(), anyList());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void reconstructsTenantContextAndMaterializesRequestedSubagent() throws Exception {
        HarnessAgent parent = mock(HarnessAgent.class);
        ReActAgent child = mock(ReActAgent.class);
        UUID orgId = UUID.randomUUID();
        when(parent.createSubagentIfPresent(eq("researcher"), any(RuntimeContext.class)))
                .thenReturn(Optional.of(child));
        when(child.call(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        ignored -> {
                            assertThat(TenantContextHolder.getOrgId()).isEqualTo(orgId.toString());
                            return Mono.deferContextual(
                                    reactorContext -> {
                                        String propagatedOrgId =
                                                reactorContext.get(TenantRlsWebFilter.ORG_ID_KEY);
                                        assertThat(propagatedOrgId).isEqualTo(orgId.toString());
                                        return Mono.just(
                                                Msg.builder()
                                                        .role(MsgRole.ASSISTANT)
                                                        .textContent("durable research result")
                                                        .build());
                                    });
                        });
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chatPersistence,
                        orchestration,
                        mock(WorkspaceArtifactService.class),
                        mock(WorkspaceCheckpointRestoreService.class),
                        Optional.empty());
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
                        orgId,
                        runId,
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        agentRunId,
                        "researcher",
                        "sub-session-1",
                        "member",
                        "standard",
                        2,
                        100_000,
                        "Research",
                        "{\"prompt\":\"Investigate the issue\","
                                + "\"_runtime\":{\"sandboxIsolationKey\":\"run/shared\","
                                + "\"modelId\":\"enterprise-large\"}}",
                        WorkspaceIsolationMode.NONE);

        String previousOrgId = UUID.randomUUID().toString();
        TenantContextHolder.setOrgId(previousOrgId);
        var result = executor.execute(request);

        assertThat(result.outputJson()).contains("durable research result");
        assertThat(TenantContextHolder.getOrgId()).isEqualTo(previousOrgId);
        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(parent).createSubagentIfPresent(eq("researcher"), context.capture());
        assertThat(context.getValue().getSessionId()).isEqualTo("sub-session-1");
        assertThat(context.getValue().getUserId()).isEqualTo(userId.toString());
        assertThat(context.getValue().get(SandboxIsolationOverride.class).key())
                .isEqualTo("run/shared");
        String selectedModelId = context.getValue().get(ContextWindowAwareModel.MODEL_ID_KEY);
        assertThat(selectedModelId).isEqualTo("enterprise-large");
        String contextAgentRunId =
                context.getValue().get(RunOrchestrationService.ATTR_AGENT_RUN_ID);
        assertThat(contextAgentRunId).isEqualTo(agentRunId.toString());
        assertThat(TenantContext.from(context.getValue()).orgId()).isEqualTo(orgId.toString());
        verify(child).call(anyList(), eq(context.getValue()));
    }

    @Test
    void persistsFinalCoordinatorContinuationExactlyOnceByRun() throws Exception {
        HarnessAgent parent = mock(HarnessAgent.class);
        Msg finalReply =
                Msg.builder().role(MsgRole.ASSISTANT).textContent("final durable answer").build();
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(finalReply));
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        UUID runId = UUID.randomUUID();
        when(orchestration.hasUnsettledChildren(runId)).thenReturn(false);
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chatPersistence,
                        orchestration,
                        mock(WorkspaceArtifactService.class),
                        mock(WorkspaceCheckpointRestoreService.class),
                        Optional.empty());
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
                        orgId,
                        runId,
                        UUID.randomUUID(),
                        userId,
                        agentId,
                        sessionId,
                        UUID.randomUUID(),
                        "assistant",
                        null,
                        "member",
                        "standard",
                        2,
                        100_000,
                        "Continue coordinator",
                        "{\"continuation\":true,\"prompt\":\"Use child results\"}",
                        WorkspaceIsolationMode.NONE);

        var result = executor.execute(request);

        assertThat(result.outputJson()).contains("final durable answer");
        verify(chatPersistence)
                .saveAssistantMessageForRun(
                        any(TenantContext.class),
                        eq(sessionId),
                        eq(agentId),
                        eq(runId),
                        eq(finalReply.getContent()));
    }

    @Test
    void restoresDirectChatFromPersistedPromptAndSavesItsFinalReply() throws Exception {
        HarnessAgent parent = mock(HarnessAgent.class);
        Msg finalReply =
                Msg.builder().role(MsgRole.ASSISTANT).textContent("recovered answer").build();
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(finalReply));
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        when(orchestration.hasUnsettledChildren(any())).thenReturn(false);
        SaasProperties properties = new SaasProperties();
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chatPersistence,
                        orchestration,
                        mock(WorkspaceArtifactService.class),
                        mock(WorkspaceCheckpointRestoreService.class),
                        Optional.empty());
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
                        UUID.randomUUID(),
                        runId,
                        taskId,
                        UUID.randomUUID(),
                        agentId,
                        sessionId,
                        UUID.randomUUID(),
                        "assistant",
                        null,
                        "member",
                        "standard",
                        2,
                        100_000,
                        "Original title",
                        "{\"prompt\":\"Process inputs/report.xlsx\","
                                + "\"_runtime\":{\"directChat\":true,\"modelId\":\"qwen-long\"}}",
                        WorkspaceIsolationMode.NONE);

        executor.execute(request);

        ArgumentCaptor<Msg> input = ArgumentCaptor.forClass(Msg.class);
        verify(parent).call(input.capture(), any(RuntimeContext.class));
        assertThat(input.getValue().getId()).isEqualTo("task-" + taskId);
        assertThat(input.getValue().getTextContent()).isEqualTo("Process inputs/report.xlsx");
        verify(chatPersistence)
                .saveAssistantMessageForRun(
                        any(TenantContext.class),
                        eq(sessionId),
                        eq(agentId),
                        eq(runId),
                        eq(finalReply.getContent()));
    }

    @Test
    void publishesCheckpointWhenAgentExecutionFailsSoRetryCanRestoreIt() {
        HarnessAgent parent = mock(HarnessAgent.class);
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("agent failed")));
        SaasProperties properties = new SaasProperties();
        properties.getSandbox().setEnabled(true);
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        WorkspaceArtifactService artifacts = mock(WorkspaceArtifactService.class);
        WorkspaceCheckpointRestoreService restore = mock(WorkspaceCheckpointRestoreService.class);
        UUID orgId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(restore.prepare(
                        any(TenantContext.class),
                        eq(orgId),
                        eq(attemptId),
                        eq("worker-test"),
                        eq(WorkspaceIsolationMode.ATTEMPT_ISOLATED)))
                .thenReturn(Optional.empty());
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        mock(ChatPersistenceService.class),
                        mock(RunOrchestrationService.class),
                        artifacts,
                        restore,
                        Optional.of(
                                mock(
                                        io.agentscope.harness.agent.filesystem.remote.store
                                                .BaseStore.class)));
        ExecutionRequest request =
                new ExecutionRequest(
                        attemptId,
                        "worker-test",
                        orgId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "assistant",
                        null,
                        "member",
                        "standard",
                        1,
                        100,
                        "Fail after work",
                        "{}",
                        WorkspaceIsolationMode.ATTEMPT_ISOLATED);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent failed");

        verify(artifacts)
                .publish(
                        eq(request.orgId()),
                        eq(request.runId()),
                        eq(request.taskId()),
                        eq(request.attemptId()),
                        any(),
                        any(WorkspaceCheckpointContext.class));
    }

    @Test
    void surfacesCheckpointFailureWhenAgentExecutionAlsoFails() {
        HarnessAgent parent = mock(HarnessAgent.class);
        IllegalStateException agentFailure = new IllegalStateException("agent failed");
        when(parent.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.error(agentFailure));
        SaasProperties properties = new SaasProperties();
        properties.getSandbox().setEnabled(true);
        properties.getOrchestration().setWorkerExecutionTimeoutSeconds(5);
        WorkspaceArtifactService artifacts = mock(WorkspaceArtifactService.class);
        IllegalStateException checkpointFailure =
                new IllegalStateException("checkpoint publication failed");
        doThrow(checkpointFailure)
                .when(artifacts)
                .publish(any(), any(), any(), any(), any(), any());
        WorkspaceCheckpointRestoreService restore = mock(WorkspaceCheckpointRestoreService.class);
        when(restore.prepare(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        mock(ChatPersistenceService.class),
                        mock(RunOrchestrationService.class),
                        artifacts,
                        restore,
                        Optional.empty());
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "assistant",
                        null,
                        "member",
                        "standard",
                        1,
                        100,
                        "Fail after work",
                        "{}",
                        WorkspaceIsolationMode.ATTEMPT_ISOLATED);

        assertThatThrownBy(() -> executor.execute(request))
                .isSameAs(checkpointFailure)
                .satisfies(
                        error -> assertThat(error.getSuppressed()).containsExactly(agentFailure));
    }

    @Test
    void rejectsReadOnlyModeUntilAReadOnlyAdapterIsConfigured() {
        HarnessAgent parent = mock(HarnessAgent.class);
        SaasProperties properties = new SaasProperties();
        ChatPersistenceService chatPersistence = mock(ChatPersistenceService.class);
        RunOrchestrationService orchestration = mock(RunOrchestrationService.class);
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker-test",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "assistant",
                        null,
                        "member",
                        "standard",
                        1,
                        100,
                        "Read only",
                        "{}",
                        WorkspaceIsolationMode.USER_SHARED_READ_ONLY);
        HarnessDurableTaskExecutor executor =
                new HarnessDurableTaskExecutor(
                        parent,
                        new ObjectMapper(),
                        properties,
                        chatPersistence,
                        orchestration,
                        mock(WorkspaceArtifactService.class),
                        mock(WorkspaceCheckpointRestoreService.class),
                        Optional.empty());

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only workspace adapter");
    }
}
