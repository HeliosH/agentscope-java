/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.app.memory.RuntimeBodyGcJob;
import io.agentscope.saas.app.memory.RuntimeBodyService;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.orchestration.ContextCheckpointRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "saas.runtime-archive.body-gc-enabled=false")
@ActiveProfiles("local")
class CheckpointTailIntegrationTest {
    @Autowired RuntimeMessageRepository messages;
    @Autowired RuntimeBodyRepository bodies;
    @Autowired ContextCheckpointRepository checkpoints;
    @Autowired RunOrchestrationRepository runRepository;
    @Autowired RunOrchestrationService runs;
    @Autowired ObjectMapper mapper;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    @Autowired
    @Qualifier("adminTransactionOperations")
    TransactionOperations admin;

    private TestDatabaseMapper database;
    private UUID org, user, agent, session;
    private RunOrchestrationService.RunHandle run;
    private RuntimeContext context;
    private SaasProperties properties;
    private RecordingStore store;
    private PgSessionArchiveStore archive;
    private CheckpointTailCodec codec;
    private DurableContextCheckpointService service;
    private ContextCheckpointStore port;
    private RuntimeMessageRepository.Scope scope;

    @BeforeEach
    void seed() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.insertInvocationOrg(org, "checkpoint-" + org);
        database.insertInvocationUser(user, org, user + "@checkpoint.test");
        database.insertRuntimeArchiveAgent(agent, org, user);
        database.insertRuntimeArchiveSession(session, org, user, agent);
        var tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        run = runs.createDirectRun(tenant, agent, session, null, "checkpoint fixture");
        context =
                RuntimeContext.builder()
                        .userId(user.toString())
                        .sessionId(session.toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                        .put(RunOrchestrationService.ATTR_RUN_ID, run.runId().toString())
                        .build();
        properties = new SaasProperties();
        properties.getRuntimeArchive().setInlineMaxBytes(256);
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(256);
        store = new RecordingStore();
        var bodyService = new RuntimeBodyService(bodies, messages, provider(), properties, manager);
        archive =
                new PgSessionArchiveStore(
                        messages, runRepository, properties, manager, bodyService);
        codec = new CheckpointTailCodec(archive, bodies, bodyService, properties);
        service =
                new DurableContextCheckpointService(checkpoints, admin, mapper, codec, properties);
        port =
                new DurableContextCheckpointFactory(service)
                        .create(org, run.runId(), run.rootAgentRunId(), context, "assistant");
        scope =
                new RuntimeMessageRepository.Scope(
                        org, user, agent, session, "assistant", session.toString());
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void reusesCommittedBodyWithoutObjectIoAndPreservesMetadata() {
        Msg source = message("source", "a".repeat(12000));
        source.getMetadata().put("view-note", "keep-me");
        archive.append(context, "assistant", session.toString(), List.of(source));
        int puts = store.puts.get(), gets = store.gets.get();
        port.save(draft(List.of(source)));
        port.save(draft(List.of(source)));
        assertThat(store.puts.get()).isEqualTo(puts);
        assertThat(store.gets.get()).isEqualTo(gets);
        var saved = checkpoints.findLatest(org, run.runId(), run.rootAgentRunId()).orElseThrow();
        assertThat(saved.retainedTailJson()).hasSizeLessThan(2000).doesNotContain("a".repeat(3000));
        var envelope = envelope();
        assertThat(envelope.archiveCursor()).isEqualTo(1);
        assertThat(envelope.archiveStreamId()).isNotNull();
        assertThat(bodies.references(envelope.items().get(0).bodyId())).isEqualTo(3);
        assertThat(StepSnapshot.fingerprint(port.latest().orElseThrow().retainedTail()))
                .isEqualTo(StepSnapshot.fingerprint(List.of(source)));
    }

    @Test
    void aggregateBudgetOffloadsSmallMessagesWithoutTruncatingWorkingWindow() {
        properties.getRuntimeArchive().setLargeBodiesEnabled(false);
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(4096);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(4096);
        var window =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> message("aggregate-" + i, "x".repeat(700)))
                        .toList();
        archive.append(context, "assistant", session.toString(), window);
        assertThat(store.puts.get()).isZero();
        port.save(draft(window));
        var envelope = envelope();
        long offloaded = envelope.items().stream().filter(item -> item.bodyId() != null).count();
        assertThat(offloaded).isPositive().isLessThan(window.size());
        assertThat(store.puts.get()).isEqualTo((int) offloaded);
        assertThat(envelope.items()).hasSize(window.size());
        assertThat(CheckpointTailCodec.bytes(JsonUtils.getJsonCodec().toJson(envelope)))
                .isLessThanOrEqualTo(4096 - CheckpointTailCodec.bytes("\"summary\"") - 2);
        for (var item : envelope.items())
            if (item.bodyId() != null) assertThat(bodies.references(item.bodyId())).isEqualTo(1);
        assertThat(StepSnapshot.fingerprint(port.latest().orElseThrow().retainedTail()))
                .isEqualTo(StepSnapshot.fingerprint(window));
    }

    @Test
    void aggregateOffloadReusesSourceBodiesWithoutExtraPutOrGet() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(4096);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(4096);
        var window =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> message("reuse-" + i, "x".repeat(700)))
                        .toList();
        archive.append(context, "assistant", session.toString(), window);
        int puts = store.puts.get(), gets = store.gets.get();
        port.save(draft(window));
        assertThat(store.puts.get()).isEqualTo(puts);
        assertThat(store.gets.get()).isEqualTo(gets);
        assertThat(envelope().items()).anyMatch(item -> item.bodyId() != null);
        assertThat(StepSnapshot.fingerprint(port.latest().orElseThrow().retainedTail()))
                .isEqualTo(StepSnapshot.fingerprint(window));
    }

    @Test
    void largestSavingsOffloadsOnlyNecessaryItemAndPreservesOrder() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(32768);
        var window =
                List.of(
                        Msg.builder().role(MsgRole.SYSTEM).textContent("x".repeat(300)).build(),
                        Msg.builder().role(MsgRole.SYSTEM).textContent("x".repeat(600)).build(),
                        Msg.builder().role(MsgRole.SYSTEM).textContent("x".repeat(2400)).build());
        var binding = new CheckpointTailCodec.Binding(context, "assistant");
        var inline = codec.prepare(org, run.runId(), binding, window, 1048576);
        long budget =
                CheckpointTailCodec.bytes(referencedJson(inline.envelope(), 2, window.get(2)));
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(budget + 11);
        port.save(draft(window));
        assertThat(envelope().items().get(0).bodyId()).isNull();
        assertThat(envelope().items().get(1).bodyId()).isNull();
        assertThat(envelope().items().get(2).bodyId()).isNotNull();
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(StepSnapshot.fingerprint(port.latest().orElseThrow().retainedTail()))
                .isEqualTo(StepSnapshot.fingerprint(window));
    }

    @Test
    void adaptiveOffloadStillRollsBackAllReferencesAndCollectsOrphans() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(16384);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(2048);
        var window =
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(
                                i ->
                                        Msg.builder()
                                                .role(MsgRole.SYSTEM)
                                                .textContent("x".repeat(2000))
                                                .build())
                        .toList();
        var failed = mock(ContextCheckpointRepository.class, delegatesTo(checkpoints));
        doThrow(new IllegalStateException("adaptive pin failure"))
                .when(failed)
                .insertBodyReference(any(), any(), any());
        var failing = new DurableContextCheckpointService(failed, admin, mapper, codec, properties);
        assertThatThrownBy(
                        () ->
                                failing.save(
                                        org,
                                        run.runId(),
                                        draft(window),
                                        new CheckpointTailCodec.Binding(context, "assistant")))
                .hasMessage("adaptive pin failure");
        assertThat(checkpoints.findLatest(org, run.runId(), run.rootAgentRunId())).isEmpty();
        assertThat(store.puts.get()).isGreaterThan(1);
        new RuntimeBodyGcJob(bodies, admin, provider(), properties)
                .collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).isEmpty();
    }

    @Test
    void fittingWindowDoesNotOffloadSmallMessages() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(4096);
        Msg summary = Msg.builder().role(MsgRole.SYSTEM).textContent("small summary").build();
        port.save(draft(List.of(summary)));
        assertThat(store.puts.get()).isZero();
        assertThat(envelope().items()).allMatch(item -> item.bodyId() == null);
        assertThat(port.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo("small summary");
    }

    @Test
    void jsonbFormattingDoesNotChangeCanonicalRecoveryBudget() throws Exception {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(4096);
        Msg summary = Msg.builder().role(MsgRole.SYSTEM).textContent("x".repeat(700)).build();
        port.save(draft(List.of(summary)));
        String compact = JsonUtils.getJsonCodec().toJson(envelope());
        long budget = CheckpointTailCodec.bytes(compact);
        String formatted =
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(mapper.readTree(compact));
        assertThat(CheckpointTailCodec.bytes(formatted)).isGreaterThan(budget);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(budget);
        var binding = new CheckpointTailCodec.Binding(context, "assistant");
        assertThat(codec.restore(org, run.runId(), binding, formatted).get(0).getTextContent())
                .isEqualTo(summary.getTextContent());
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(budget - 1);
        assertThatThrownBy(() -> codec.restore(org, run.runId(), binding, compact))
                .hasMessage("CHECKPOINT_JSON_BYTE_LIMIT");
        assertThat(store.puts.get()).isZero();
        assertThat(store.gets.get()).isZero();
    }

    @Test
    void exactUtf8JsonBoundaryAccountsForEscapedStringsBeforeAnyUpload() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(32768);
        Msg summary =
                Msg.builder()
                        .id("escaped")
                        .role(MsgRole.SYSTEM)
                        .textContent("\u4e2d\u6587\"\\\n".repeat(400))
                        .build();
        var binding = new CheckpointTailCodec.Binding(context, "assistant");
        var inline = codec.prepare(org, run.runId(), binding, List.of(summary), 1048576);
        assertThat(store.puts.get()).isZero();
        long exactBudget = CheckpointTailCodec.bytes(referencedJson(inline.envelope(), 0, summary));
        assertThatThrownBy(
                        () ->
                                codec.prepare(
                                        org,
                                        run.runId(),
                                        binding,
                                        List.of(summary),
                                        exactBudget - 1))
                .hasMessage("CHECKPOINT_JSON_BYTE_LIMIT");
        assertThat(store.puts.get()).isZero();
        var fitted = codec.prepare(org, run.runId(), binding, List.of(summary), exactBudget);
        assertThat(CheckpointTailCodec.bytes(fitted.json())).isEqualTo(exactBudget);
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(codec.restore(org, run.runId(), binding, fitted.json()).get(0).getTextContent())
                .isEqualTo(summary.getTextContent());
    }

    @Test
    void irreducibleAggregateHeadersFailBeforeAnyObjectUpload() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(16384);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(2048);
        var window =
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(
                                i ->
                                        Msg.builder()
                                                .id("header-" + i)
                                                .role(MsgRole.SYSTEM)
                                                .textContent("x".repeat(2000))
                                                .metadata(Map.of("working-header", "y".repeat(800)))
                                                .build())
                        .toList();
        assertThatThrownBy(() -> port.save(draft(window))).hasMessage("CHECKPOINT_JSON_BYTE_LIMIT");
        assertThat(store.puts.get()).isZero();
        assertThat(checkpoints.findLatest(org, run.runId(), run.rootAgentRunId())).isEmpty();
    }

    @Test
    void summaryAndPendingOperationsConsumeTheSameBudget() {
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(16384);
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(4096);
        var window =
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(
                                i ->
                                        Msg.builder()
                                                .role(MsgRole.SYSTEM)
                                                .textContent("x".repeat(700))
                                                .build())
                        .toList();
        var draft = draft(window);
        var withSummary =
                new ContextCheckpointStore.Draft(
                        draft.identity(),
                        draft.executionLease(),
                        draft.stepId(),
                        draft.historyHash(),
                        "s".repeat(1000),
                        window,
                        draft.retainedFactsVersion(),
                        List.of("operation-" + "p".repeat(500)),
                        draft.workspaceVersion());
        port.save(withSummary);
        var saved = checkpoints.findLatest(org, run.runId(), run.rootAgentRunId()).orElseThrow();
        assertThat(envelope().items()).anyMatch(item -> item.bodyId() != null);
        assertThat(
                        CheckpointTailCodec.bytes(saved.retainedTailJson())
                                + CheckpointTailCodec.bytes(
                                        JsonUtils.getJsonCodec().toJson(saved.summary()))
                                + CheckpointTailCodec.bytes(saved.pendingOperationsJson()))
                .isLessThanOrEqualTo(4096);
        assertThat(port.latest().orElseThrow().pendingOperationIds())
                .containsExactly("operation-" + "p".repeat(500));
    }

    @Test
    void restoredWindowCountsHeadersAgainstFullWindowBudget() {
        Msg source = message("header-budget", "x".repeat(4000));
        source.getMetadata().put("large-header", "y".repeat(1500));
        archive.append(context, "assistant", session.toString(), List.of(source));
        port.save(draft(List.of(source)));
        properties
                .getRuntimeArchive()
                .setMaxWindowBytes(envelope().items().get(0).contentBytes() + 1);
        assertThatThrownBy(port::latest)
                .hasMessageStartingWith("Unable to deserialize context checkpoint")
                .hasRootCauseMessage("CHECKPOINT_TAIL_BYTE_LIMIT");
    }

    @Test
    void inlineLengthCannotUnderstateRecoveryBudget() {
        Msg summary = Msg.builder().role(MsgRole.SYSTEM).textContent("small").build();
        properties.getRuntimeArchive().setCheckpointInlineMaxBytes(4096);
        port.save(draft(List.of(summary)));
        var saved = envelope();
        var item = saved.items().get(0);
        var tampered =
                new CheckpointTailCodec.Envelope(
                        2,
                        saved.scope(),
                        saved.archiveStreamId(),
                        saved.archiveCursor(),
                        saved.tailHash(),
                        List.of(new CheckpointTailCodec.Item(null, 1, item.messageJson())));
        assertThatThrownBy(
                        () ->
                                codec.restore(
                                        org,
                                        run.runId(),
                                        new CheckpointTailCodec.Binding(context, "assistant"),
                                        JsonUtils.getJsonCodec().toJson(tampered)))
                .hasMessage("CHECKPOINT_INTEGRITY_FAILED");
    }

    @Test
    void expandedWorkingViewGetsItsOwnBodyInsteadOfRawUserText() {
        Msg raw = message("upload", "process attachment");
        archive.append(context, "assistant", session.toString(), List.of(raw));
        Msg expanded =
                SessionArchiveStore.projection(
                        raw,
                        raw.withContent(
                                List.of(
                                        TextBlock.builder()
                                                .text("file-version-v1-" + "x".repeat(9000))
                                                .build())));
        port.save(draft(List.of(expanded)));
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(port.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo(expanded.getTextContent());
        assertThat(
                        archive.readWindow(context, "assistant", session.toString(), 0L, null, 1)
                                .get(0)
                                .message()
                                .getTextContent())
                .isEqualTo(raw.getTextContent());
    }

    @Test
    void approvedToolStateIsNotReplacedByArchivedPendingState() {
        var pending =
                ToolUseBlock.builder()
                        .id("call")
                        .name("execute")
                        .input(Map.of("command", "x".repeat(4000)))
                        .build();
        Msg original =
                Msg.builder().id("tool-call").role(MsgRole.ASSISTANT).content(pending).build();
        archive.append(context, "assistant", session.toString(), List.of(original));
        Msg approved = original.withContent(List.of(pending.withState(ToolCallState.ALLOWED)));
        port.save(draft(List.of(approved)));
        assertThat(store.puts.get()).isEqualTo(2);
        assertThat(
                        port.latest()
                                .orElseThrow()
                                .retainedTail()
                                .get(0)
                                .getFirstContentBlock(ToolUseBlock.class)
                                .getState())
                .isEqualTo(ToolCallState.ALLOWED);
    }

    @Test
    void checkpointPinsBodyAfterTranscriptDeletionAndCascadedPinDeletionAllowsGc() {
        Msg source = message("source", "a".repeat(4000));
        archive.append(context, "assistant", session.toString(), List.of(source));
        port.save(draft(List.of(source)));
        UUID id = envelope().items().get(0).bodyId();
        String key = bodies.findOwned(scope, id).orElseThrow().objectKey();
        new TransactionTemplate(manager)
                .executeWithoutResult(tx -> messages.deleteSession(org, user, session));
        var gc = new RuntimeBodyGcJob(bodies, admin, provider(), properties);
        gc.collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).containsKey(key);
        assertThat(port.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo(source.getTextContent());
        new TransactionTemplate(manager)
                .executeWithoutResult(tx -> database.deleteRuntimeCheckpoints(org, run.runId()));
        gc.collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).doesNotContainKey(key);
    }

    @Test
    void saasPortRetainsCompleteWorkingWindowWhileDisabledModeKeepsLegacyTail() {
        List<Msg> working =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> message("window-" + i, "question-" + i))
                        .toList();
        assertThat(port.retainedWindow(working)).hasSize(30).startsWith(working.get(0));
        properties.getRuntimeArchive().setLightweightCheckpointsEnabled(false);
        assertThat(port.retainedWindow(working)).hasSize(20).startsWith(working.get(10));
    }

    @Test
    void uncommittedSourceFailsBeforeObjectUpload() {
        assertThatThrownBy(() -> port.save(draft(List.of(message("missing", "a".repeat(9000))))))
                .hasMessage("CHECKPOINT_SOURCE_NOT_COMMITTED");
        assertThat(store.puts.get()).isZero();
        assertThat(checkpoints.findLatest(org, run.runId(), run.rootAgentRunId())).isEmpty();
    }

    @Test
    void staleLeaseFailsBeforeShadowUpload() {
        Msg summary =
                Msg.builder()
                        .id("summary")
                        .role(MsgRole.SYSTEM)
                        .textContent("x".repeat(9000))
                        .build();
        database.updateAttemptExpiry(run.rootAttemptId(), OffsetDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> port.save(draft(List.of(summary))))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(store.puts.get()).isZero();
    }

    @Test
    void referenceFailureRollsBackCheckpointAndLeavesCollectableShadow() {
        var failed = mock(ContextCheckpointRepository.class, delegatesTo(checkpoints));
        doThrow(new IllegalStateException("pin fixture failure"))
                .when(failed)
                .insertBodyReference(any(), any(), any());
        var failing = new DurableContextCheckpointService(failed, admin, mapper, codec, properties);
        var bound = new CheckpointTailCodec.Binding(context, "assistant");
        Msg summary =
                Msg.builder()
                        .id("summary")
                        .role(MsgRole.SYSTEM)
                        .textContent("x".repeat(9000))
                        .build();
        assertThatThrownBy(() -> failing.save(org, run.runId(), draft(List.of(summary)), bound))
                .hasMessage("pin fixture failure");
        assertThat(checkpoints.findLatest(org, run.runId(), run.rootAgentRunId())).isEmpty();
        assertThat(store.objects).hasSize(1);
        new RuntimeBodyGcJob(bodies, admin, provider(), properties)
                .collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).isEmpty();
    }

    @Test
    void missingBodyDoesNotReturnPreviewOrOlderCheckpoint() {
        Msg source = message("source", "a".repeat(4000));
        archive.append(context, "assistant", session.toString(), List.of(source));
        port.save(draft(List.of(source)));
        store.objects.clear();
        assertThatThrownBy(port::latest)
                .hasMessageStartingWith("Unable to deserialize context checkpoint");
    }

    @Test
    void foreignBindingCannotReadCheckpointBodies() {
        Msg source = message("source", "a".repeat(4000));
        archive.append(context, "assistant", session.toString(), List.of(source));
        port.save(draft(List.of(source)));
        int gets = store.gets.get();
        var tenant =
                new TenantContext(
                        org.toString(), UUID.randomUUID().toString(), "member", "standard", 2, 0);
        var foreign =
                RuntimeContext.builder()
                        .userId(tenant.userId())
                        .sessionId(session.toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                        .put(RunOrchestrationService.ATTR_RUN_ID, run.runId().toString())
                        .build();
        assertThatThrownBy(
                        () ->
                                codec.restore(
                                        org,
                                        run.runId(),
                                        new CheckpointTailCodec.Binding(foreign, "assistant"),
                                        checkpoints
                                                .findLatest(org, run.runId(), run.rootAgentRunId())
                                                .orElseThrow()
                                                .retainedTailJson()))
                .hasMessage("Runtime run is not owned by caller");
        assertThat(store.gets.get()).isEqualTo(gets);
    }

    @Test
    void legacyArrayRoundTripsWithoutEnablingObjectSnapshots() {
        Msg small = message("legacy", "small");
        ContextCheckpointStore legacy =
                new DurableContextCheckpointFactory(service)
                        .create(org, run.runId(), run.rootAgentRunId());
        legacy.save(draft(List.of(small)));
        assertThat(legacy.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo("small");
        assertThat(store.puts.get()).isZero();
    }

    @Test
    void legacyWrappedJsonDocumentRemainsReadable() {
        Msg small = message("wrapped", "old-h2-document");
        var draft = draft(List.of(small));
        var legacy =
                new ContextCheckpointRepository.NewCheckpoint(
                        UUID.randomUUID(),
                        org,
                        run.runId(),
                        run.rootTaskId(),
                        run.rootAgentRunId(),
                        run.rootAttemptId(),
                        1,
                        "old-step",
                        draft.historyHash(),
                        "old-summary",
                        JsonUtils.getJsonCodec()
                                .toJson(JsonUtils.getJsonCodec().toJson(List.of(small))),
                        null,
                        JsonUtils.getJsonCodec().toJson("[]"),
                        "old-workspace",
                        OffsetDateTime.now());
        admin.executeWithoutResult(tx -> checkpoints.insert(legacy));
        assertThat(port.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo("old-h2-document");
    }

    @Test
    void tamperedWorkingHeaderFailsWholeWindowFingerprint() {
        Msg source = message("source", "a".repeat(4000));
        archive.append(context, "assistant", session.toString(), List.of(source));
        port.save(draft(List.of(source)));
        var envelope = envelope();
        var item = envelope.items().get(0);
        String header =
                item.messageJson().replace("\"metadata\":{}", "\"metadata\":{\"unexpected\":true}");
        assertThat(header).isNotEqualTo(item.messageJson());
        var modified =
                new CheckpointTailCodec.Envelope(
                        2,
                        envelope.scope(),
                        envelope.archiveStreamId(),
                        envelope.archiveCursor(),
                        envelope.tailHash(),
                        List.of(
                                new CheckpointTailCodec.Item(
                                        item.bodyId(), item.contentBytes(), header)));
        assertThatThrownBy(
                        () ->
                                codec.restore(
                                        org,
                                        run.runId(),
                                        new CheckpointTailCodec.Binding(context, "assistant"),
                                        JsonUtils.getJsonCodec().toJson(modified)))
                .hasMessage("CHECKPOINT_INTEGRITY_FAILED");
    }

    @Test
    void metadataLimitRejectsBeforeShadowUploadAndDisabledWritesRemainCompatible() {
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(1024);
        Msg summary =
                Msg.builder()
                        .id("summary")
                        .role(MsgRole.SYSTEM)
                        .textContent("x".repeat(4000))
                        .metadata(Map.of("oversize-header", "y".repeat(2000)))
                        .build();
        assertThatThrownBy(() -> port.save(draft(List.of(summary))))
                .hasMessage("CHECKPOINT_JSON_BYTE_LIMIT");
        assertThat(store.puts.get()).isZero();
        properties.getRuntimeArchive().setCheckpointMaxJsonBytes(1048576);
        properties.getRuntimeArchive().setLightweightCheckpointsEnabled(false);
        port.save(draft(List.of(message("small", "compatible"))));
        assertThat(
                        checkpoints
                                .findLatest(org, run.runId(), run.rootAgentRunId())
                                .orElseThrow()
                                .retainedTailJson())
                .startsWith("[");
        assertThat(port.latest().orElseThrow().retainedTail().get(0).getTextContent())
                .isEqualTo("compatible");
    }

    private CheckpointTailCodec.Envelope envelope() {
        return JsonUtils.getJsonCodec()
                .fromJson(
                        checkpoints
                                .findLatest(org, run.runId(), run.rootAgentRunId())
                                .orElseThrow()
                                .retainedTailJson(),
                        CheckpointTailCodec.Envelope.class);
    }

    private static String referencedJson(
            CheckpointTailCodec.Envelope inline, int index, Msg message) {
        var header =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(message), Map.class);
        header.remove("content");
        var items = new java.util.ArrayList<>(inline.items());
        items.set(
                index,
                new CheckpointTailCodec.Item(
                        new UUID(0, 0),
                        CheckpointTailCodec.bytes(SessionArchiveStore.payload(message)),
                        JsonUtils.getJsonCodec().toJson(header)));
        return JsonUtils.getJsonCodec()
                .toJson(
                        new CheckpointTailCodec.Envelope(
                                2,
                                inline.scope(),
                                inline.archiveStreamId(),
                                inline.archiveCursor(),
                                inline.tailHash(),
                                items));
    }

    private ContextCheckpointStore.Draft draft(List<Msg> tail) {
        return new ContextCheckpointStore.Draft(
                new StepSnapshot.Identity(
                        run.runId().toString(),
                        run.rootAgentRunId().toString(),
                        run.rootTaskId().toString(),
                        run.rootAttemptId().toString()),
                new ExecutionLeaseSnapshot("direct:" + run.runId()),
                "step-test",
                StepSnapshot.fingerprint(tail),
                "summary",
                tail,
                "facts-v1",
                List.of(),
                "workspace-v1");
    }

    private static Msg message(String id, String text) {
        return Msg.builder().id(id).role(MsgRole.USER).textContent(text).build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<FileObjectStore> provider() {
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private static class RecordingStore implements FileObjectStore {
        final Map<String, byte[]> objects = new HashMap<>();
        final AtomicInteger puts = new AtomicInteger(), gets = new AtomicInteger();

        public String backend() {
            return "pg";
        }

        public void put(FileObject object) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            puts.incrementAndGet();
            objects.put(object.objectKey(), object.content().clone());
        }

        public byte[] get(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            gets.incrementAndGet();
            return objects.containsKey(key) ? objects.get(key).clone() : null;
        }

        public void delete(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            objects.remove(key);
        }
    }
}
