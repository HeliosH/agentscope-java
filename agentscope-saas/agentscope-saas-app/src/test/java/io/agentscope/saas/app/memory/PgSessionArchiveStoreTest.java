/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest
@ActiveProfiles("local")
public class PgSessionArchiveStoreTest {
    @TempDir Path workspace;
    @Autowired PgSessionArchiveStore archive;
    @Autowired RuntimeMessageRepository repository;
    @Autowired RunOrchestrationRepository runs;
    TestDatabaseMapper database;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired PlatformTransactionManager transactionManager;
    private UUID org, user, agent, session;
    private RuntimeContext context;
    private TenantContext tenant;

    @BeforeEach
    void seed() {
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        database.insertInvocationOrg(org, "archive-" + org);
        database.insertInvocationUser(user, org, user + "@archive.test");
        database.insertRuntimeArchiveAgent(agent, org, user);
        database.insertRuntimeArchiveSession(session, org, user, agent);
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        context = context(tenant, agent, session);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void appendIsIdempotentAndPreservesCompleteStructuredSource() {
        Msg first = message("a", "first"), second = message("b", "second");
        assertThat(archive.append(context, "assistant", key(), List.of(first, second)).appended())
                .isEqualTo(2);
        for (int i = 0; i < 100; i++)
            assertThat(
                            archive.append(context, "assistant", key(), List.of(first, second))
                                    .appended())
                    .isZero();
        var result = archive.readWindow(context, "assistant", key(), 0L, null, 100);
        assertThat(result).extracting(SessionArchiveStore.Entry::seq).containsExactly(1L, 2L);
        assertThat(result)
                .extracting(entry -> entry.message().getTextContent())
                .containsExactly("first", "second");
        assertThat(
                        archive.append(
                                        context,
                                        "assistant",
                                        key(),
                                        List.of(second, message("c", "third")))
                                .lastSeq())
                .isEqualTo(3);
    }

    @Test
    void contentConflictRollsBackNewRowsAndSequence() {
        archive.append(context, "assistant", key(), List.of(message("a", "original")));
        assertThatThrownBy(
                        () ->
                                archive.append(
                                        context,
                                        "assistant",
                                        key(),
                                        List.of(
                                                message("new", "must roll back"),
                                                message("a", "different"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_MESSAGE_ID_CONTENT_CONFLICT");
        assertThat(archive.readWindow(context, "assistant", key(), 0L, null, 10)).hasSize(1);
        assertThat(
                        archive.append(
                                        context,
                                        "assistant",
                                        key(),
                                        List.of(message("next", "next")))
                                .lastSeq())
                .isEqualTo(2);
    }

    @Test
    void projectionsOnlyReferToPreviouslyCommittedSources() {
        Msg original = message("a", "very long original source");
        Msg projection =
                SessionArchiveStore.projection(
                        original,
                        original.withContent(List.of(TextBlock.builder().text("preview").build())));
        assertThatThrownBy(() -> archive.append(context, "assistant", key(), List.of(projection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_PROJECTION_SOURCE_MISSING");
        archive.append(context, "assistant", key(), List.of(original));
        assertThat(archive.append(context, "assistant", key(), List.of(projection)).appended())
                .isZero();
        assertThat(
                        archive.readWindow(context, "assistant", key(), null, null, 10)
                                .get(0)
                                .message()
                                .getTextContent())
                .isEqualTo("very long original source");
    }

    @Test
    void sourcesExcludeInjectedSystemMemoryAndSummary() {
        var system = Msg.builder().role(MsgRole.SYSTEM).textContent("private prompt").build();
        var summary =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("__compaction_summary__")
                        .textContent("summary")
                        .build();
        var recalled =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("long_term_memory")
                        .textContent("recalled")
                        .build();
        assertThat(
                        archive.append(
                                        context,
                                        "assistant",
                                        key(),
                                        List.of(system, summary, recalled, message("a", "source")))
                                .appended())
                .isEqualTo(1);
    }

    @Test
    void childProjectionRecoversCommittedFullParentSourceWithinSameOwnedSession() {
        Msg source = message("parent", "complete parent source");
        archive.append(context, "assistant", key(), List.of(source));
        Msg projection =
                SessionArchiveStore.projection(
                        source,
                        source.withContent(List.of(TextBlock.builder().text("preview").build())));
        assertThat(archive.append(context, "child", key(), List.of(projection)).appended())
                .isEqualTo(1);
        assertThat(
                        archive.readWindow(context, "child", key(), 0L, null, 10)
                                .get(0)
                                .message()
                                .getTextContent())
                .isEqualTo("complete parent source");
    }

    @Test
    void pagesAreOrderedAndByteBoundedInBothDirections() {
        var properties = new SaasProperties();
        properties.getRuntimeArchive().setMaxMessageBytes(300);
        properties.getRuntimeArchive().setMaxWindowBytes(300);
        var bounded = new PgSessionArchiveStore(repository, runs, properties, transactionManager);
        bounded.append(
                context,
                "assistant",
                key(),
                List.of(
                        message("a", "x".repeat(100)),
                        message("b", "y".repeat(100)),
                        message("c", "z".repeat(100))));
        var first = bounded.page(tenant, agent, session, "assistant", key(), 0L, null, 10);
        assertThat(first.items()).extracting(SessionArchiveStore.Entry::seq).containsExactly(1L);
        assertThat(first.hasMore()).isTrue();
        var next =
                bounded.page(
                        tenant, agent, session, "assistant", key(), first.nextAfterSeq(), null, 10);
        assertThat(next.items()).extracting(SessionArchiveStore.Entry::seq).containsExactly(2L);
        var latest = bounded.page(tenant, agent, session, "assistant", key(), null, null, 10);
        assertThat(latest.items()).extracting(SessionArchiveStore.Entry::seq).containsExactly(3L);
        assertThat(latest.hasMore()).isTrue();
        var older =
                bounded.page(
                        tenant,
                        agent,
                        session,
                        "assistant",
                        key(),
                        null,
                        latest.nextBeforeSeq(),
                        10);
        assertThat(older.items()).extracting(SessionArchiveStore.Entry::seq).containsExactly(2L);
    }

    @Test
    void foreignUserCannotReadOrWriteEvenWithSameOrganizationAndSession() {
        archive.append(context, "assistant", key(), List.of(message("a", "private")));
        var foreign =
                new TenantContext(
                        org.toString(), UUID.randomUUID().toString(), "member", "standard", 2, 0);
        var foreignContext = context(foreign, agent, session);
        assertThat(archive.readWindow(foreignContext, "assistant", key(), null, null, 10))
                .isEmpty();
        assertThat(archive.search(foreignContext, null, "private", 10)).isEmpty();
        assertThatThrownBy(
                        () ->
                                archive.append(
                                        foreignContext,
                                        "assistant",
                                        key(),
                                        List.of(message("b", "bad"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentDuplicateAdmissionAllocatesExactlyOneSequence() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Msg source = message("a", "same");
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return archive.append(context, "assistant", key(), List.of(source));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return archive.append(context, "assistant", key(), List.of(source));
                            });
            start.countDown();
            assertThat(
                            first.get(10, TimeUnit.SECONDS).appended()
                                    + second.get(10, TimeUnit.SECONDS).appended())
                    .isEqualTo(1);
            assertThat(archive.readWindow(context, "assistant", key(), 0L, null, 10)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void searchTreatsWildcardsLiterallyAndReturnsBoundedPreviews() {
        archive.append(
                context,
                "assistant",
                key(),
                List.of(message("a", "literal %_ term"), message("b", "ordinary term")));
        var hits = archive.search(context, null, "%_", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).preview()).contains("literal");
        assertThat(hits.get(0).preview().length()).isLessThanOrEqualTo(2048);
    }

    @Test
    void sameRuntimeKeyInDifferentRootSessionsCannotSelectWrongPage() {
        UUID other = UUID.randomUUID();
        database.insertRuntimeArchiveSession(other, org, user, agent);
        archive.append(context, "assistant", "shared", List.of(message("a", "first session")));
        archive.append(
                context(tenant, agent, other),
                "assistant",
                "shared",
                List.of(message("a", "other session")));
        assertThat(
                        archive.page(tenant, agent, session, "assistant", "shared", 0L, null, 10)
                                .items())
                .extracting(entry -> entry.message().getTextContent())
                .containsExactly("first session");
        assertThat(archive.page(tenant, agent, other, "assistant", "shared", 0L, null, 10).items())
                .extracting(entry -> entry.message().getTextContent())
                .containsExactly("other session");
    }

    @Test
    void authoritativeArchiveFailureStopsBeforeModelInvocation() {
        SessionArchiveStore failed = mock(SessionArchiveStore.class);
        when(failed.append(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("archive-fixture");
        HarnessAgent harness =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .memory(MemoryConfig.builder().sessionArchiveStore(failed).build())
                        .build();
        assertThatThrownBy(
                        () ->
                                harness.call(List.of(message("a", "input")), context)
                                        .block(Duration.ofSeconds(10)))
                .isInstanceOf(SessionArchiveStore.ArchiveCommitException.class);
        verify(model, never()).stream(any(), any(), any());
    }

    @Test
    void realHarnessCommitsInputAndFinalReplyWithoutFilesystemOrMemoryModelCalls() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("archive-fixture");
        when(model.stream(any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .finishReason("stop")
                                        .build()));
        HarnessAgent harness =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .memory(MemoryConfig.builder().sessionArchiveStore(archive).build())
                        .build();
        harness.call(List.of(message("a", "question")), context).block(Duration.ofSeconds(20));
        assertThat(archive.readWindow(context, "assistant", key(), 0L, null, 10))
                .extracting(entry -> entry.message().getTextContent())
                .containsExactly("question", "done");
    }

    @Test
    void realHarnessExpandsAttachmentButArchivesOriginalUserText() throws Exception {
        Files.writeString(workspace.resolve("attachment.txt"), "attachment contents");
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("archive-attachment-fixture");
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> input = invocation.getArgument(0);
                            assertThat(input)
                                    .anySatisfy(
                                            msg ->
                                                    assertThat(msg.getTextContent())
                                                            .contains("attachment contents"));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .finishReason("stop")
                                            .build());
                        });
        HarnessAgent harness =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(new LocalFilesystemSpec().project(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableSubagents()
                        .memory(MemoryConfig.builder().sessionArchiveStore(archive).build())
                        .build();
        harness.call(List.of(message("attachment-question", "Read @./attachment.txt")), context)
                .block(Duration.ofSeconds(20));
        assertThat(archive.readWindow(context, "assistant", key(), 0L, null, 10))
                .extracting(entry -> entry.message().getTextContent())
                .containsExactly("Read @./attachment.txt", "done");
    }

    @Test
    void realHarnessCanResumeApprovedToolWithoutMutatingArchivedSource() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("archive-hitl-fixture");
        ToolUseBlock request =
                ToolUseBlock.builder()
                        .id("call")
                        .name("guarded")
                        .input(Map.of("value", "original"))
                        .build();
        when(model.stream(any(), any(), any()))
                .thenReturn(
                        Flux.just(ChatResponse.builder().content(List.of(request)).build()),
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .finishReason("stop")
                                        .build()));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("guarded")
                                .description("approval fixture")
                                .inputSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("value", Map.of("type", "string"))))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState permission) {
                        return Mono.just(PermissionDecision.ask("Confirm fixture"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(
                                ToolResultBlock.text("executed:" + param.getInput().get("value")));
                    }
                });
        HarnessAgent harness =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .memory(MemoryConfig.builder().sessionArchiveStore(archive).build())
                        .build();
        Msg paused =
                harness.call(List.of(message("question", "run task")), context)
                        .block(Duration.ofSeconds(20));
        assertThat(paused.getGenerateReason()).isEqualTo(GenerateReason.PERMISSION_ASKING);
        ToolUseBlock pending =
                harness.getDelegate().getAgentState(context).getContext().stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                        .filter(use -> "call".equals(use.getId()))
                        .findFirst()
                        .orElseThrow();
        Msg confirmation =
                Msg.builder()
                        .id("confirmation")
                        .role(MsgRole.USER)
                        .textContent("confirmed")
                        .metadata(
                                Map.of(
                                        Msg.METADATA_CONFIRM_RESULTS,
                                        List.of(new ConfirmResult(true, pending, null))))
                        .build();
        Msg done = harness.call(List.of(confirmation), context).block(Duration.ofSeconds(20));
        assertThat(done.getTextContent()).isEqualTo("done");
        var rows = archive.readWindow(context, "assistant", key(), 0L, null, 100);
        assertThat(rows)
                .extracting(entry -> entry.message().getRole())
                .contains(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL);
        assertThat(
                        rows.stream()
                                .flatMap(
                                        entry ->
                                                entry
                                                        .message()
                                                        .getContentBlocks(ToolUseBlock.class)
                                                        .stream())
                                .filter(use -> "call".equals(use.getId()))
                                .toList())
                .singleElement()
                .satisfies(
                        use -> {
                            assertThat(use.getInput()).containsEntry("value", "original");
                            assertThat(use.getState()).isEqualTo(ToolCallState.ASKING);
                        });
        assertThat(rows.get(rows.size() - 1).message().getTextContent()).isEqualTo("done");
    }

    @Test
    void checkpointFailureLeavesToolSourceCommittedInPg() {
        var effectCalls = new AtomicInteger();
        var checkpoints = new AtomicInteger();
        var failure = new IllegalStateException("fixture checkpoint unavailable");
        context.put(
                StepSnapshot.Identity.class,
                new StepSnapshot.Identity(
                        "fixture-run", "fixture-agent", "fixture-task", "fixture-attempt"));
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    var committed = archive.readWindow(context, "assistant", key(), 0L, null, 100);
                    assertThat(committed)
                            .anySatisfy(
                                    entry ->
                                            assertThat(entry.message().getRole())
                                                    .isEqualTo(MsgRole.TOOL));
                    checkpoints.incrementAndGet();
                    throw failure;
                });
        Model model = completionModel();
        HarnessAgent harness = completionHarness(model, effectCalls);
        assertThatThrownBy(
                        () ->
                                harness.call(List.of(message("question", "execute")), context)
                                        .block(Duration.ofSeconds(20)))
                .isSameAs(failure);
        assertThat(checkpoints.get()).isEqualTo(1);
        assertThat(effectCalls.get()).isEqualTo(1);
        var persisted = archive.readWindow(context, "assistant", key(), 0L, null, 100);
        assertThat(persisted)
                .extracting(entry -> entry.message().getRole())
                .contains(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL);
        assertThat(persisted)
                .noneSatisfy(
                        entry -> assertThat(entry.message().getTextContent()).isEqualTo("done"));
    }

    @Test
    void checkpointReadsCommittedToolSourceBeforeSuccessfulFinalReply() {
        var effectCalls = new AtomicInteger();
        var checkpoints = new AtomicInteger();
        context.put(
                StepSnapshot.Identity.class,
                new StepSnapshot.Identity(
                        "fixture-run", "fixture-agent", "fixture-task", "fixture-attempt"));
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    var committed = archive.readWindow(context, "assistant", key(), 0L, null, 100);
                    assertThat(committed)
                            .anySatisfy(
                                    entry ->
                                            assertThat(
                                                            entry.message()
                                                                    .getContentBlocks(
                                                                            ToolResultBlock.class))
                                                    .isNotEmpty());
                    checkpoints.incrementAndGet();
                    return new ContextCheckpointStore.StoredCheckpoint(
                            checkpoints.get(), draft.historyHash());
                });
        HarnessAgent harness = completionHarness(completionModel(), effectCalls);
        assertThat(
                        harness.call(List.of(message("question", "execute")), context)
                                .block(Duration.ofSeconds(20))
                                .getTextContent())
                .isEqualTo("done");
        assertThat(checkpoints.get()).isEqualTo(1);
        assertThat(effectCalls.get()).isEqualTo(1);
        var rows = archive.readWindow(context, "assistant", key(), 0L, null, 100);
        assertThat(rows.get(rows.size() - 1).message().getTextContent()).isEqualTo("done");
    }

    private Model completionModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("archive-checkpoint-fixture");
        when(model.stream(any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("effect-call")
                                                                .name("archive_effect")
                                                                .input(Map.of())
                                                                .build()))
                                        .build()),
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .finishReason("stop")
                                        .build()));
        return model;
    }

    private HarnessAgent completionHarness(Model model, AtomicInteger calls) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("archive_effect")
                                .description("fixture")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState permission) {
                        return Mono.just(PermissionDecision.allow("fixture"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        calls.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("effect completed"));
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
                .memory(MemoryConfig.builder().sessionArchiveStore(archive).build())
                .build();
    }

    @Test
    void largeHistoryReadsOnlyRequestedWindow() {
        Msg seed = message("_archive_seed_", "capacity");
        archive.append(context, "assistant", key(), List.of(seed));
        var stream = repository.findStream(org, user, agent, "assistant", key()).orElseThrow();
        database.createArchiveDigestFixture();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        tx -> {
                            database.insertArchiveCapacityFixture(
                                    stream.id(),
                                    org,
                                    user,
                                    SessionArchiveStore.payload(seed),
                                    100000);
                            repository.advance(stream.scope(), stream.id(), 100000, "load-100000");
                        });
        var page = archive.page(tenant, agent, session, "assistant", key(), 99995L, null, 3);
        assertThat(page.items())
                .extracting(SessionArchiveStore.Entry::seq)
                .containsExactly(99996L, 99997L, 99998L);
        assertThat(page.items())
                .extracting(entry -> entry.message().getId())
                .containsExactly("load-99996", "load-99997", "load-99998");
        assertThat(page.hasMore()).isTrue();
        assertThat(archive.page(tenant, agent, session, "assistant", key(), null, null, 2).items())
                .extracting(SessionArchiveStore.Entry::seq)
                .containsExactly(99999L, 100000L);
    }

    public static String digest(String payload) {
        return SessionArchiveStore.digest(JsonUtils.getJsonCodec().fromJson(payload, Msg.class));
    }

    private String key() {
        return session.toString();
    }

    private static Msg message(String id, String text) {
        return Msg.builder().id(id).role(MsgRole.USER).textContent(text).build();
    }

    private static RuntimeContext context(TenantContext tenant, UUID agent, UUID session) {
        return RuntimeContext.builder()
                .userId(tenant.userId())
                .sessionId(session.toString())
                .put(TenantContext.ATTR_KEY, tenant)
                .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                .build();
    }
}
