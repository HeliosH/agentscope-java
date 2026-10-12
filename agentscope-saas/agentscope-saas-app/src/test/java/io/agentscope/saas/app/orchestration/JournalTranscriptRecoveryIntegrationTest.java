/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fresh runtime state must rebuild journal results without re-executing external effects. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class JournalTranscriptRecoveryIntegrationTest {
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    @TempDir Path workspace;
    @Autowired RunOrchestrationService runs;
    @Autowired DurableToolExecutionJournalFactory journals;
    @Autowired DurableContextCheckpointFactory checkpoints;
    @Autowired PgSessionArchiveStore archive;
    @Autowired RuntimeMessageRepository messages;
    private final TestDatabaseMapper database;

    @Autowired
    JournalTranscriptRecoveryIntegrationTest(@Qualifier("adminDataSource") DataSource dataSource) {
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
    }

    @BeforeEach
    void bindTenant() {
        TenantContextHolder.setOrgId(ORG.toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshHarnessRebuildsCommittedJournalAcrossSourceAndCheckpointFailures(
            boolean sourceFailure) {
        UUID agent = UUID.randomUUID(), session = UUID.randomUUID();
        database.insertAgent(agent, ORG, USER, "journal-recovery-" + agent);
        database.insertChatSession(session, ORG, USER, agent, "journal recovery");
        var tenant =
                new TenantContext(ORG.toString(), USER.toString(), "member", "standard", 2, 100000);
        var run = runs.createDirectRun(tenant, agent, session, null, "execute once");
        var ctx = context(tenant, agent, session, run);
        var durable = checkpoints.create(ORG, run.runId(), run.rootAgentRunId(), ctx, "assistant");
        var injected = new AtomicBoolean();
        ContextCheckpointStore failingCheckpoint =
                new ContextCheckpointStore() {
                    @Override
                    public StoredCheckpoint save(Draft draft) {
                        if (!sourceFailure
                                && draft.pendingOperationIds().isEmpty()
                                && injected.compareAndSet(false, true)) {
                            throw new IllegalStateException("fixture post-tool checkpoint failure");
                        }
                        return durable.save(draft);
                    }

                    @Override
                    public List<Msg> retainedWindow(List<Msg> window) {
                        return durable.retainedWindow(window);
                    }
                };
        SessionArchiveStore failingArchive = spy(archive);
        doAnswer(
                        call -> {
                            List<Msg> batch = call.getArgument(3);
                            if (sourceFailure
                                    && batch.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.hasContentBlocks(
                                                                    ToolResultBlock.class))) {
                                injected.set(true);
                                throw new IllegalStateException(
                                        "fixture post-journal source failure");
                            }
                            return call.callRealMethod();
                        })
                .when(failingArchive)
                .append(any(), any(), any(), any());
        ctx.put(ContextCheckpointStore.class, failingCheckpoint);
        var effects = new AtomicInteger();
        var permissions = new AtomicInteger();
        var use =
                ToolUseBlock.builder()
                        .id("effect-call")
                        .name("durable_effect")
                        .input(Map.of())
                        .build();
        Model first = model(Flux.just(ChatResponse.builder().content(List.of(use)).build()));
        HarnessAgent original = harness(first, effects, permissions, failingArchive);
        var question =
                Msg.builder()
                        .id("question-" + run.runId())
                        .role(MsgRole.USER)
                        .textContent("execute once")
                        .build();
        assertThatThrownBy(() -> original.call(List.of(question), ctx).block(TIMEOUT))
                .hasMessageContaining(
                        sourceFailure
                                ? "Runtime archive commit failed"
                                : "fixture post-tool checkpoint failure");
        assertThat(injected).isTrue();
        assertThat(effects).hasValue(1);
        assertThat(database.toolOperationState(run.runId(), "effect-call").status())
                .isEqualTo("SUCCEEDED");
        var pending = durable.latest().orElseThrow();
        assertThat(pending.pendingOperationIds()).containsExactly(run.runId() + ":effect-call");
        assertThat(pending.retainedTail())
                .noneMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
        var before = archive.readWindow(ctx, "assistant", session.toString(), 0L, null, 100);
        assertThat(
                        before.stream()
                                .filter(e -> e.message().hasContentBlocks(ToolResultBlock.class))
                                .count())
                .isEqualTo(sourceFailure ? 0 : 1);

        // No original AgentState or journal instance survives this reconstruction.
        var fresh = context(tenant, agent, session, run);
        fresh.put(
                ContextCheckpointStore.class,
                checkpoints.create(ORG, run.runId(), run.rootAgentRunId(), fresh, "assistant"));
        var modelCalls = new AtomicInteger();
        Model finalModel =
                model(
                        Flux.defer(
                                () -> {
                                    modelCalls.incrementAndGet();
                                    return Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text("done")
                                                                            .build()))
                                                    .finishReason("stop")
                                                    .build());
                                }));
        HarnessAgent recovered = harness(finalModel, effects, permissions, archive);
        Msg redelivery =
                Msg.builder()
                        .id("redelivery-" + run.runId())
                        .role(MsgRole.USER)
                        .textContent("execute once")
                        .build();
        assertThat(recovered.call(List.of(redelivery), fresh).block(TIMEOUT).getTextContent())
                .isEqualTo("done");
        assertThat(effects).hasValue(1);
        assertThat(permissions.get()).isGreaterThanOrEqualTo(2);
        assertThat(modelCalls).hasValue(1);
        assertThat(database.countToolOperationOutbox(run.runId(), "effect-call")).isEqualTo(1);
        var rows = archive.readWindow(fresh, "assistant", session.toString(), 0L, null, 100);
        var results =
                rows.stream()
                        .filter(e -> e.message().hasContentBlocks(ToolResultBlock.class))
                        .toList();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).message().getId()).startsWith("tool-result-");
        assertThat(
                        results.get(0)
                                .message()
                                .getContentBlocks(ToolResultBlock.class)
                                .get(0)
                                .getOutput())
                .extracting(block -> ((TextBlock) block).getText())
                .containsExactly("effect committed");
        if (!sourceFailure) {
            assertThat(results.get(0).message().getId())
                    .isEqualTo(
                            before.stream()
                                    .filter(
                                            e ->
                                                    e.message()
                                                            .hasContentBlocks(
                                                                    ToolResultBlock.class))
                                    .findFirst()
                                    .orElseThrow()
                                    .message()
                                    .getId());
        }
        assertThat(
                        messages.findStream(ORG, USER, agent, "assistant", session.toString())
                                .orElseThrow()
                                .lastSeq())
                .isEqualTo(rows.size());
    }

    private RuntimeContext context(
            TenantContext tenant, UUID agent, UUID session, RunOrchestrationService.RunHandle run) {
        return RuntimeContext.builder()
                .userId(USER.toString())
                .sessionId(session.toString())
                .put(TenantContext.ATTR_KEY, tenant)
                .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                .put(RunOrchestrationService.ATTR_RUN_ID, run.runId().toString())
                .put(RunOrchestrationService.ATTR_AGENT_RUN_ID, run.rootAgentRunId().toString())
                .put(
                        StepSnapshot.Identity.class,
                        new StepSnapshot.Identity(
                                run.runId().toString(),
                                run.rootAgentRunId().toString(),
                                run.rootTaskId().toString(),
                                run.rootAttemptId().toString()))
                .put(
                        ExecutionLeaseSnapshot.class,
                        new ExecutionLeaseSnapshot("direct:" + run.runId()))
                .put(ToolExecutionJournal.class, journals.create(ORG, run.runId()))
                .build();
    }

    private static Model model(Flux<ChatResponse> response) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("journal-recovery-fixture");
        when(model.stream(any(), any(), any())).thenReturn(response);
        return model;
    }

    private HarnessAgent harness(
            Model model,
            AtomicInteger effects,
            AtomicInteger permissions,
            SessionArchiveStore store) {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("durable_effect")
                                .description("fixture")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState permission) {
                        permissions.incrementAndGet();
                        return Mono.just(PermissionDecision.allow("fixture"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        effects.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("effect committed"));
                    }
                });
        return HarnessAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableSubagents()
                .memory(MemoryConfig.builder().sessionArchiveStore(store).build())
                .build();
    }
}
