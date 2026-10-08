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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentStateNamespace;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.repository.AgentRepository;
import io.agentscope.saas.domain.repository.ChatMessageRepository;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest(
        properties = {
            "saas.runtime-archive.body-gc-enabled=false",
            "saas.orchestration.session-fence-poll-millis=100"
        })
@ActiveProfiles("local")
class SessionGenerationIntegrationTest {
    @Autowired RunOrchestrationRepository repository;
    @Autowired RunOrchestrationService runs;
    @Autowired ChatPersistenceService chats;
    @Autowired PgSessionArchiveStore archive;
    @Autowired DurableContextCheckpointFactory checkpoints;
    @Autowired SessionRunFenceService fences;
    @Autowired SessionGenerationMiddleware middleware;
    @Autowired WorkspaceArtifactService artifacts;
    @Autowired AgentRepository agents;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired RuntimeMessageRepository runtimeMessages;
    @Autowired FileRepository fileRepository;
    @Autowired FileVersionRepository versions;
    @Autowired FileAttachmentRepository attachments;
    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;
    @Autowired ObjectMapper mapper;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    private UUID org, user, agent, session;
    private TenantContext tenant;
    private RunOrchestrationService.RunHandle run;
    private RuntimeContext context;
    private ContextCheckpointStore checkpoint;
    private FileCatalogService files;
    private RecordingStore objects;
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        var db = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        db.insertInvocationOrg(org, "generation-" + org);
        db.insertInvocationUser(user, org, user + "@generation.test");
        db.insertRuntimeArchiveAgent(agent, org, user);
        db.insertRuntimeArchiveSession(session, org, user, agent);
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        run =
                runs.createDirectRun(
                        tenant, agent, session, null, "generation fixture", "request-original");
        context = context(run);
        checkpoint =
                checkpoints.create(org, run.runId(), run.rootAgentRunId(), context, "assistant");
        tx = new TransactionTemplate(manager);
        objects = new RecordingStore();
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(objects);
        var properties = new SaasProperties();
        properties.getFileStore().setEnabled(true);
        files =
                new FileCatalogService(
                        fileRepository,
                        versions,
                        attachments,
                        orgs,
                        users,
                        provider,
                        mapper,
                        properties,
                        repository);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void resetRevokesParentAndChildrenClearsCheckpointsAndKeepsUserFiles() {
        runs.createSubagentTask(
                tenant,
                agent,
                run.runId(),
                run.rootAgentRunId(),
                "child",
                "assistant",
                "child-session",
                "{}",
                null);
        Msg source = message("before reset");
        archive.append(context, "assistant", session.toString(), List.of(source));
        checkpoint.save(draft(List.of(source)));
        tx.executeWithoutResult(
                status ->
                        files.recordWorkspaceFile(
                                tenant,
                                agent,
                                session,
                                "inputs/keep.txt",
                                new byte[] {1, 2, 3},
                                "text/plain",
                                FileCatalogService.SOURCE_WORKSPACE_UPLOAD,
                                Map.of()));
        chats.resetSession(session);
        assertThat(
                        repository
                                .findSessionFence(session, org, user, agent)
                                .orElseThrow()
                                .generation())
                .isEqualTo(1);
        assertThat(repository.findOwnedRun(run.runId(), org, user, agent).orElseThrow().status())
                .isEqualTo("CANCELLED");
        assertThat(repository.findTasks(run.runId(), org))
                .allMatch(task -> task.status().equals("CANCELLED"));
        assertThat(repository.findAgentRuns(run.runId(), org))
                .allMatch(child -> child.status().equals("CANCELLED"));
        assertThat(repository.findAttempts(run.runId(), org))
                .allMatch(attempt -> attempt.status().equals("CANCELLED"));
        assertThat(checkpoint.latest()).isEmpty();
        assertThat(
                        archive.page(
                                        tenant,
                                        agent,
                                        session,
                                        "assistant",
                                        session.toString(),
                                        0L,
                                        null,
                                        20)
                                .items())
                .isEmpty();
        assertThat(files.readCurrentFile(tenant, "inputs/keep.txt")).isPresent();
    }

    @Test
    void oldReplyAndIdempotentReplayAreRejectedButNewGenerationCanReply() {
        chats.resetSession(session);
        assertThatThrownBy(
                        () ->
                                chats.saveAssistantMessageForRun(
                                        tenant,
                                        session,
                                        agent,
                                        run.runId(),
                                        List.of(TextBlock.builder().text("late").build())))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThatThrownBy(() -> runs.findByIdempotencyKey(tenant, agent, "request-original"))
                .isInstanceOf(SessionExecutionRevokedException.class);
        var next = runs.createDirectRun(tenant, agent, session, null, "fresh", "new-request");
        assertThat(
                        repository
                                .findCurrentSessionFence(next.runId(), org, user, agent)
                                .orElseThrow()
                                .generation())
                .isEqualTo(1);
        assertThat(
                        chats.saveAssistantMessageForRun(
                                tenant,
                                session,
                                agent,
                                next.runId(),
                                List.of(TextBlock.builder().text("fresh answer").build())))
                .isNotNull();
        assertThat(messages.maxSeq(session)).isEqualTo(1);
    }

    @Test
    void staleArchiveAndCheckpointWritesCannotRepopulateResetHistory() {
        chats.resetSession(session);
        assertThatThrownBy(
                        () ->
                                archive.append(
                                        context,
                                        "assistant",
                                        session.toString(),
                                        List.of(message("late source"))))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThatThrownBy(() -> checkpoint.save(draft(List.of(message("late source")))))
                .isInstanceOf(ToolLeaseLostException.class);
        assertThat(
                        archive.page(
                                        tenant,
                                        agent,
                                        session,
                                        "assistant",
                                        session.toString(),
                                        0L,
                                        null,
                                        20)
                                .items())
                .isEmpty();
    }

    @Test
    void completedRunAuditRemainsButOldIdempotencyAndWorkspacePublicationAreRevoked() {
        runs.markSucceeded(tenant, agent, run.runId());
        chats.resetSession(session);
        assertThat(repository.findOwnedRun(run.runId(), org, user, agent).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> runs.findByIdempotencyKey(tenant, agent, "request-original"))
                .isInstanceOf(SessionExecutionRevokedException.class);
        var workspace = new WorkspaceCheckpointContext(true);
        workspace.projectionSucceeded(0);
        workspace.statePersisted();
        workspace.sandboxStopped();
        assertThatThrownBy(
                        () ->
                                artifacts.publish(
                                        org,
                                        run.runId(),
                                        run.rootTaskId(),
                                        run.rootAttemptId(),
                                        null,
                                        workspace))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                repository.reopenRun(
                                                        run.runId(),
                                                        org,
                                                        java.time.OffsetDateTime.now())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sessionOnlyReplyAlsoRejectsOldGeneration() {
        chats.resetSession(session);
        assertThatThrownBy(
                        () ->
                                chats.saveAssistantMessageForSession(
                                        tenant,
                                        session,
                                        agent,
                                        0,
                                        List.of(
                                                TextBlock.builder()
                                                        .text("old non-run answer")
                                                        .build())))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThat(
                        chats.saveAssistantMessageForSession(
                                tenant,
                                session,
                                agent,
                                1,
                                List.of(TextBlock.builder().text("new answer").build())))
                .isNotNull();
    }

    @Test
    void deleteMakesLiveAndRecoveryBindingsUnavailable() {
        var binding = fences.bind(context);
        chats.deleteSession(session);
        assertThat(fences.current(binding)).isFalse();
        assertThatThrownBy(() -> fences.bind(context))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThat(sessions.findById(session)).isEmpty();
        assertThat(repository.findOwnedRun(run.runId(), org, user, agent)).isEmpty();
    }

    @Test
    void markingReadCannotRestoreOldCountersOrRecreateDeletedSession() {
        chats.saveUserMessage(tenant, session, agent, "old question");
        chats.resetSession(session);
        assertThat(sessions.markReadOwned(session, org, user, agent)).isTrue();
        assertThat(sessions.findById(session).orElseThrow().getMessageCount()).isZero();
        assertThat(sessions.findById(session).orElseThrow().getLastMessage()).isNull();
        chats.deleteSession(session);
        assertThat(sessions.markReadOwned(session, org, user, agent)).isFalse();
        assertThat(sessions.findById(session)).isEmpty();
    }

    @Test
    void revokedStreamingInvocationIsCancelledWithoutWaitingForTheNextModelCall() {
        var cancelled = new AtomicBoolean();
        var events =
                middleware.onAgent(
                        null,
                        context,
                        new AgentInput(List.of()),
                        input ->
                                Flux.concat(
                                                Flux.<AgentEvent>just(
                                                        new AgentStartEvent(
                                                                null, "fixture", "assistant")),
                                                Flux.never())
                                        .doOnCancel(() -> cancelled.set(true)));
        StepVerifier.create(events)
                .expectNextCount(1)
                .then(() -> chats.resetSession(session))
                .expectError(SessionExecutionRevokedException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void monitoringFailureCancelsTheInvocationInsteadOfOnlySendingAnError() {
        var failing = spy(fences);
        doThrow(new IllegalStateException("monitor DB fixture")).when(failing).current(any());
        var properties = new SaasProperties();
        properties.getOrchestration().setSessionFencePollMillis(100);
        var monitor = new SessionGenerationMiddleware(failing, properties);
        var cancelled = new AtomicBoolean();
        var events =
                monitor.onAgent(
                        null,
                        context,
                        new AgentInput(List.of()),
                        input ->
                                Flux.concat(
                                                Flux.<AgentEvent>just(
                                                        new AgentStartEvent(
                                                                null, "fixture", "assistant")),
                                                Flux.never())
                                        .doOnCancel(() -> cancelled.set(true)));
        StepVerifier.create(events)
                .expectNextCount(1)
                .expectErrorMessage("monitor DB fixture")
                .verify(Duration.ofSeconds(5));
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void childAndNonRunSessionsGetTheSameGenerationWithoutChangingLogicalIds() {
        chats.resetSession(session);
        var next = runs.createDirectRun(tenant, agent, session, null, "fresh");
        var child =
                RuntimeContext.builder()
                        .copyAttributesFrom(context(next))
                        .userId(user.toString())
                        .sessionId("child-session")
                        .build();
        middleware
                .onAgent(null, child, new AgentInput(List.of()), input -> Flux.empty())
                .blockLast();
        assertThat(child.get(AgentStateNamespace.class).value()).isEqualTo("generation-1");
        assertThat(child.getSessionId()).isEqualTo("child-session");
        var nonRun =
                RuntimeContext.builder()
                        .userId(user.toString())
                        .sessionId(session.toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                        .build();
        middleware
                .onAgent(null, nonRun, new AgentInput(List.of()), input -> Flux.empty())
                .blockLast();
        assertThat(nonRun.get(AgentStateNamespace.class).value()).isEqualTo("generation-1");
        var localChild =
                RuntimeContext.builder()
                        .copyAttributesFrom(nonRun)
                        .userId(user.toString())
                        .sessionId("local-child")
                        .build();
        middleware
                .onAgent(null, localChild, new AgentInput(List.of()), input -> Flux.empty())
                .blockLast();
        assertThat(localChild.get(AgentStateNamespace.class).value()).isEqualTo("generation-1");
        archive.append(
                localChild, "assistant", "local-child", List.of(message("current local child")));
        chats.resetSession(session);
        assertThatThrownBy(
                        () ->
                                archive.append(
                                        nonRun,
                                        "assistant",
                                        session.toString(),
                                        List.of(message("old non-run"))))
                .isInstanceOf(SessionExecutionRevokedException.class);
    }

    @Test
    void foreignEmployeeCannotBindTheRunOrItsStateGeneration() {
        var foreign =
                new TenantContext(
                        org.toString(), UUID.randomUUID().toString(), "member", "standard", 2, 0);
        var ctx =
                RuntimeContext.builder()
                        .userId(foreign.userId())
                        .sessionId(session.toString())
                        .put(TenantContext.ATTR_KEY, foreign)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                        .put(RunOrchestrationService.ATTR_RUN_ID, run.runId().toString())
                        .build();
        assertThatThrownBy(() -> fences.bind(ctx))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThat(repository.findCurrentSessionFence(run.runId(), org, user, agent)).isPresent();
    }

    @Test
    void resetAfterFenceLockWaitsAndThenClearsTheCommittedReply() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var completion =
                new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            var started = new CountDownLatch(1);
            tx.executeWithoutResult(
                    status -> {
                        repository
                                .lockCurrentSessionFence(run.runId(), org, user, agent)
                                .orElseThrow();
                        var reset =
                                executor.submit(
                                        () -> {
                                            TenantContextHolder.setOrgId(org.toString());
                                            try {
                                                started.countDown();
                                                chats.resetSession(session);
                                            } finally {
                                                TenantContextHolder.clear();
                                            }
                                        });
                        completion.set(reset);
                        try {
                            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                            assertThatThrownBy(() -> reset.get(200, TimeUnit.MILLISECONDS))
                                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                        } catch (InterruptedException error) {
                            throw new IllegalStateException(error);
                        }
                        chats.saveAssistantMessageForRun(
                                tenant,
                                session,
                                agent,
                                run.runId(),
                                List.of(TextBlock.builder().text("before reset").build()));
                    });
            completion.get().get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(messages.maxSeq(session)).isZero();
        assertThat(repository.findCurrentSessionFence(run.runId(), org, user, agent)).isEmpty();
    }

    @Test
    void resetDuringObjectUploadRejectsMetadataWithoutHoldingTheSessionLockAcrossIo()
            throws Exception {
        var fence = repository.findCurrentSessionFence(run.runId(), org, user, agent).orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        objects.entered = entered;
        objects.release = release;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var publish =
                    executor.submit(
                            () -> {
                                TenantContextHolder.setOrgId(org.toString());
                                try {
                                    tx.executeWithoutResult(
                                            status ->
                                                    files.recordWorkspaceFileForExecution(
                                                            tenant,
                                                            agent,
                                                            run.runId(),
                                                            fence,
                                                            "outputs/late.txt",
                                                            new byte[] {9},
                                                            "text/plain",
                                                            FileCatalogService
                                                                    .SOURCE_SANDBOX_PROJECTION,
                                                            Map.of()));
                                } finally {
                                    TenantContextHolder.clear();
                                }
                            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var reset =
                        executor.submit(
                                () -> {
                                    TenantContextHolder.setOrgId(org.toString());
                                    try {
                                        chats.resetSession(session);
                                    } finally {
                                        TenantContextHolder.clear();
                                    }
                                });
                reset.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> publish.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(SessionExecutionRevokedException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(files.readCurrentFile(tenant, "outputs/late.txt")).isEmpty();
        assertThat(objects.values).hasSize(1);
    }

    @Test
    void oldProjectedDeleteCannotRemoveNewGenerationArtifact() {
        var old = repository.findCurrentSessionFence(run.runId(), org, user, agent).orElseThrow();
        chats.resetSession(session);
        var next = runs.createDirectRun(tenant, agent, session, null, "fresh");
        var current =
                repository.findCurrentSessionFence(next.runId(), org, user, agent).orElseThrow();
        tx.executeWithoutResult(
                status ->
                        files.recordWorkspaceFileForExecution(
                                tenant,
                                agent,
                                next.runId(),
                                current,
                                "outputs/current.txt",
                                new byte[] {1},
                                "text/plain",
                                FileCatalogService.SOURCE_SANDBOX_PROJECTION,
                                Map.of()));
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                files.markDeletedForExecution(
                                                        tenant,
                                                        agent,
                                                        run.runId(),
                                                        old,
                                                        "outputs/current.txt")))
                .isInstanceOf(SessionExecutionRevokedException.class);
        assertThat(files.readCurrentFile(tenant, "outputs/current.txt")).isPresent();
    }

    @Test
    void resetFailureRollsBackEpochRevocationAndCheckpointRemoval() {
        Msg source = message("retained");
        archive.append(context, "assistant", session.toString(), List.of(source));
        checkpoint.save(draft(List.of(source)));
        var failed = mock(RuntimeMessageRepository.class, delegatesTo(runtimeMessages));
        doThrow(new IllegalStateException("reset fixture failure"))
                .when(failed)
                .deleteSession(any(), any(), any());
        var service =
                new ChatPersistenceService(
                        agents, sessions, messages, repository, mapper, files, failed);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.resetSession(session)))
                .hasMessage("reset fixture failure");
        assertThat(
                        repository
                                .findCurrentSessionFence(run.runId(), org, user, agent)
                                .orElseThrow()
                                .generation())
                .isZero();
        assertThat(repository.findOwnedRun(run.runId(), org, user, agent).orElseThrow().status())
                .isEqualTo("RUNNING");
        assertThat(checkpoint.latest()).isPresent();
    }

    private RuntimeContext context(RunOrchestrationService.RunHandle handle) {
        return RuntimeContext.builder()
                .userId(user.toString())
                .sessionId(session.toString())
                .put(TenantContext.ATTR_KEY, tenant)
                .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                .put(RunOrchestrationService.ATTR_RUN_ID, handle.runId().toString())
                .build();
    }

    private ContextCheckpointStore.Draft draft(List<Msg> tail) {
        return new ContextCheckpointStore.Draft(
                new StepSnapshot.Identity(
                        run.runId().toString(),
                        run.rootAgentRunId().toString(),
                        run.rootTaskId().toString(),
                        run.rootAttemptId().toString()),
                new ExecutionLeaseSnapshot("direct:" + run.runId()),
                "generation-step",
                StepSnapshot.fingerprint(tail),
                "",
                tail,
                null,
                List.of(),
                null);
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static class RecordingStore implements FileObjectStore {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        CountDownLatch entered, release;

        public String backend() {
            return "pg";
        }

        public void put(FileObject object) {
            values.put(object.objectKey(), object.content());
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("test upload timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        }

        public byte[] get(UUID org, String key) {
            return values.get(key);
        }

        public void delete(UUID org, String key) {
            values.remove(key);
        }
    }
}
