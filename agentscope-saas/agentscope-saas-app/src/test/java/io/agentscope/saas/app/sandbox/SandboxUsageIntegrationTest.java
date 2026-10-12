/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SandboxUsageGuard;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.SandboxUsageTestMapper;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.domain.sandbox.SandboxUsageGcRepository;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

@SpringBootTest(
        properties = {
            "saas.file-store.gc-enabled=false",
            "saas.runtime-archive.body-gc-enabled=false"
        })
@ActiveProfiles("local")
class SandboxUsageIntegrationTest {
    @Autowired SandboxUsageRepository repository;
    @Autowired SandboxUsageGcRepository gc;
    @Autowired RunOrchestrationRepository runRepository;
    @Autowired RunOrchestrationService runs;
    @Autowired DurableTaskLeaseService leases;
    @Autowired ChatPersistenceService chats;
    @Autowired UserRepository users;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired
    @Qualifier("adminDataSource")
    DataSource adminDataSource;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    @Autowired
    @Qualifier("adminTransactionOperations")
    TransactionOperations admin;

    private UUID org, user, agent, session;
    private TenantContext tenant;
    private RunOrchestrationService.RunHandle run;
    private RuntimeContext owner;
    private PgSandboxUsageGuard guard;
    private SaasProperties properties;
    private SandboxUsageTestMapper fixtures;
    private SandboxUsageTestMapper adminFixtures;
    private Sandbox sandbox;

    @BeforeEach
    void seed() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        var db = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        db.insertInvocationOrg(org, "sandbox-usage-" + org);
        db.insertInvocationUser(user, org, user + "@sandbox-usage.test");
        db.insertRuntimeArchiveAgent(agent, org, user);
        db.insertRuntimeArchiveSession(session, org, user, agent);
        fixtures = MyBatisRepositoryTestSupport.mapper(dataSource, SandboxUsageTestMapper.class);
        adminFixtures =
                MyBatisRepositoryTestSupport.mapper(adminDataSource, SandboxUsageTestMapper.class);
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 1, 0);
        run =
                runs.createDirectRun(
                        tenant,
                        agent,
                        session,
                        null,
                        "sandbox fixture",
                        "usage-" + UUID.randomUUID());
        assertThat(leases.activateDirect(run.rootAttemptId(), "direct:" + run.runId())).isTrue();
        owner = context(run);
        properties = new SaasProperties();
        properties.getSandbox().setIdleTtlSeconds(30);
        properties.getSandbox().setReconciliationActiveGraceSeconds(0);
        properties.getSandbox().setBackendReleaseMaxAttempts(3);
        guard = freshGuard();
        sandbox = mock(Sandbox.class);
        var state = new SandboxState() {};
        state.setSessionId("backend-" + UUID.randomUUID());
        when(sandbox.getState()).thenReturn(state);
    }

    @AfterEach
    void clear() {
        if (adminFixtures != null && org != null) adminFixtures.deleteResources(org);
        TenantContextHolder.clear();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "RUN_OPENSANDBOX_USAGE_TEST",
            matches = "true")
    void realOpenSandboxChildReadsParentInputAndSurvivesOwnerCloseUntilItFinishes()
            throws Exception {
        var options = new io.agentscope.extensions.sandbox.opensandbox.OpenSandboxClientOptions();
        options.setApiBaseUrl(
                System.getenv()
                        .getOrDefault("OPENSANDBOX_API_BASE_URL", "http://127.0.0.1:18081/v1"));
        options.setImage(
                System.getenv().getOrDefault("SAAS_SANDBOX_OPENSANDBOX_IMAGE", "ubuntu:latest"));
        options.setWorkspaceRoot("/workspace");
        options.setWaitTimeoutSeconds(180);
        properties.getSandbox().setType("opensandbox");
        properties.getSandbox().setOpenSandboxApiBaseUrl(options.getApiBaseUrl());
        properties.getSandbox().setIdleTtlSeconds(600);
        guard = freshGuard();
        var client =
                spy(
                        new io.agentscope.extensions.sandbox.opensandbox.OpenSandboxClient(
                                options, null));
        var spec = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        spec.setRoot("/workspace");
        owner.put(
                io.agentscope.harness.agent.sandbox.SandboxContext.class,
                io.agentscope.harness.agent.sandbox.SandboxContext.builder()
                        .workspaceSpec(spec)
                        .clientOptions(options)
                        .isolationScope(IsolationScope.SESSION)
                        .build());
        var manager =
                new io.agentscope.harness.agent.sandbox.SandboxManager(
                        client,
                        new io.agentscope.harness.agent.sandbox.SessionSandboxStateStore(
                                new io.agentscope.core.state.InMemoryAgentStateStore(),
                                "usage-live"),
                        "usage-live");
        var parentFilesystem =
                new io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem();
        var lifecycle =
                new io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware(
                        manager, parentFilesystem, null, guard);
        Sandbox physical = null;
        io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware childLifecycle = null;
        RuntimeContext child = null;
        try {
            lifecycle.acquireForCall(owner);
            physical = owner.get(Sandbox.class);
            assertThat(
                            physical.exec(
                                            owner,
                                            "mkdir -p /workspace/inputs /workspace/outputs; printf"
                                                    + " 'shared-input-evidence' >"
                                                    + " /workspace/inputs/receipt.txt",
                                            30)
                                    .ok())
                    .isTrue();
            var root = owner.get(SandboxUsageGuard.Binding.class);
            child =
                    RuntimeContext.builder()
                            .userId(user.toString())
                            .sessionId("child-" + UUID.randomUUID())
                            .copyAttributesFrom(owner)
                            .build();
            var childFilesystem =
                    new io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem();
            childLifecycle = lifecycle.fork(childFilesystem);
            childLifecycle.acquireForCall(child);
            var fileTools =
                    new io.agentscope.harness.agent.tool.FilesystemTool(
                            childFilesystem,
                            io.agentscope.harness.agent.workspace.WorkspacePathNormalizer.of(
                                    "/workspace"));
            assertThat(child.get(Sandbox.class)).isSameAs(physical);
            assertThat(child.get(SandboxUsageGuard.Binding.class).borrowed()).isTrue();
            assertThat(fileTools.readFile(child, "/workspace/inputs/receipt.txt", 0, 100))
                    .contains("shared-input-evidence");
            assertThatThrownBy(() -> lifecycle.observeResult(owner, null))
                    .hasMessage("SANDBOX_RESOURCE_USERS_PENDING");
            lifecycle.releaseForCall(owner);
            assertThat(resource(root).status()).isEqualTo("DRAINING");
            assertThat(
                            physical.exec(
                                            child,
                                            "cat /workspace/inputs/receipt.txt; printf"
                                                    + " 'child-output-evidence' >"
                                                    + " /workspace/outputs/child.txt",
                                            30)
                                    .stdout())
                    .contains("shared-input-evidence");
            assertThat(fileTools.readFile(child, "/workspace/outputs/child.txt", 0, 100))
                    .contains("child-output-evidence");
            childLifecycle.releaseForCall(child);
            var terminator =
                    new io.agentscope.extensions.sandbox.opensandbox.OpenSandboxTerminator(options);
            assertThat(
                            job((type, id) -> {
                                        try {
                                            terminator.terminate(id);
                                            return SandboxBackendTerminator.TerminationResult
                                                    .success();
                                        } catch (Exception error) {
                                            return SandboxBackendTerminator.TerminationResult
                                                    .failed(error.getMessage());
                                        }
                                    })
                                    .collectOnce())
                    .isEqualTo(1);
            assertThat(resource(root).status()).isEqualTo("RELEASED");
            verify(client, times(1)).create(any(), any(), any());
        } finally {
            if (childLifecycle != null && child != null) childLifecycle.releaseForCall(child);
            lifecycle.releaseForCall(owner);
            if (physical != null) physical.shutdown();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "RUN_POSTGRES_USAGE_TEST",
            matches = "true")
    void postgresRlsAndColumnGrantsProtectResourceFacts() {
        var root = active();
        TenantContextHolder.setOrgId(UUID.randomUUID().toString());
        try {
            assertThat(repository.find(org, user, UUID.fromString(root.resourceId()))).isEmpty();
            assertThat(repository.reference(org, user, UUID.fromString(root.referenceId())))
                    .isEmpty();
        } finally {
            TenantContextHolder.setOrgId(org.toString());
        }
        assertThatThrownBy(
                        () ->
                                fixtures.rewriteOwner(
                                        UUID.fromString(root.resourceId()), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(resource(root).userId()).isEqualTo(user);
    }

    @Test
    void releaseRetriesAreBoundedAndOnlyTheConfiguredDeploymentMayClaim() {
        var root = active();
        assertThat(gc.candidates("wrong-deployment", OffsetDateTime.now(), 3, 20)).isEmpty();
        assertThat(guard.close(owner, root, false)).isTrue();
        guard.released(owner, root, false);
        var calls = new AtomicInteger();
        var job =
                job(
                        (type, id) -> {
                            calls.incrementAndGet();
                            return SandboxBackendTerminator.TerminationResult.failed(
                                    "provider unavailable");
                        });
        assertThat(job.collectOnce()).isZero();
        assertThat(job.collectOnce()).isZero();
        assertThat(job.collectOnce()).isZero();
        assertThat(calls).hasValue(2);
        assertThat(resource(root).releaseAttempts()).isEqualTo(3);
        assertThat(repository.countOccupied(org, user)).isEqualTo(1);
    }

    @Test
    void separateGuardInstancesSharePhysicalResourceAtQuotaOneAndDrainBeforeReaping() {
        var root = active();
        var child = context(run);
        var childRef = freshGuard().borrow(child, root, sandbox);
        assertThat(childRef.borrowed()).isTrue();
        assertThat(childRef.resourceId()).isEqualTo(root.resourceId());
        assertThat(childRef.referenceId()).isNotEqualTo(root.referenceId());
        assertThat(repository.countOccupied(org, user)).isEqualTo(1);
        assertThatThrownBy(() -> guard.prepareCompletion(owner, root))
                .hasMessage("SANDBOX_RESOURCE_USERS_PENDING");
        assertThat(guard.close(owner, root, false)).isFalse();
        assertThat(resource(root).status()).isEqualTo("DRAINING");
        freshGuard().requireCurrent(child, childRef, sandbox);
        assertThat(freshGuard().heartbeat(child, childRef)).isTrue();
        assertThat(gc.candidates(guard.deploymentKey(), OffsetDateTime.now(), 3, 20))
                .noneMatch(r -> r.id().toString().equals(root.resourceId()));
        assertThat(freshGuard().close(child, childRef, false)).isFalse();
        var calls = new AtomicInteger();
        var job =
                job(
                        (type, id) -> {
                            calls.incrementAndGet();
                            return SandboxBackendTerminator.TerminationResult.success();
                        });
        assertThat(job.collectOnce()).isEqualTo(1);
        assertThat(resource(root).status()).isEqualTo("RELEASED");
        assertThat(job.collectOnce()).isZero();
        assertThat(calls).hasValue(1);
        assertThat(repository.countOccupied(org, user)).isZero();
    }

    @Test
    void reservationsEnforcePhysicalQuotaBeforeProviderCreation() {
        var first = guard.reserve(owner, slot(owner));
        var next =
                runs.createDirectRun(
                        tenant, agent, session, null, "next", "next-" + UUID.randomUUID());
        leases.activateDirect(next.rootAttemptId(), "direct:" + next.runId());
        var ctx = context(next);
        assertThatThrownBy(() -> freshGuard().reserve(ctx, slot(ctx)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(guard.close(owner, first, false)).isTrue();
        assertThat(resource(first).status()).isEqualTo("RELEASED");
        assertThat(freshGuard().reserve(ctx, slot(ctx))).isNotNull();
    }

    @Test
    void completionSealsAdmissionAndReleaseFailureRemainsOccupiedUntilConfirmed() {
        var root = active();
        guard.prepareCompletion(owner, root);
        assertThatThrownBy(() -> freshGuard().borrow(context(run), root, sandbox))
                .isInstanceOf(IllegalStateException.class);
        assertThat(guard.close(owner, root, false)).isTrue();
        guard.released(owner, root, false);
        assertThat(resource(root).status()).isEqualTo("RELEASE_FAILED");
        assertThat(repository.countOccupied(org, user)).isEqualTo(1);
        assertThat(
                        job((type, id) -> SandboxBackendTerminator.TerminationResult.success())
                                .collectOnce())
                .isEqualTo(1);
        assertThat(resource(root).status()).isEqualTo("RELEASED");
    }

    @Test
    void resetAndForgedScopesRevokeUseButStillAllowOwnerCleanup() {
        var root = active();
        var foreign =
                RuntimeContext.builder().userId(user.toString()).copyAttributesFrom(owner).build();
        foreign.put(SandboxRuntimeAttributes.ATTR_RUN_ID, UUID.randomUUID().toString());
        assertThatThrownBy(() -> guard.borrow(foreign, root, sandbox))
                .isInstanceOf(IllegalStateException.class);
        var forged = context(run);
        forged.put(SandboxRuntimeAttributes.ATTR_LEASE_OWNER, "wrong-owner");
        assertThatThrownBy(() -> guard.requireCurrent(forged, root, sandbox))
                .isInstanceOf(IllegalStateException.class);
        var other = mock(Sandbox.class);
        var state = new SandboxState() {};
        state.setSessionId("wrong-backend");
        when(other.getState()).thenReturn(state);
        assertThatThrownBy(() -> guard.requireCurrent(owner, root, other))
                .isInstanceOf(IllegalStateException.class);
        chats.resetSession(session);
        assertThatThrownBy(() -> guard.heartbeat(owner, root))
                .isInstanceOf(IllegalStateException.class);
        assertThat(guard.close(owner, root, false)).isTrue();
        guard.released(owner, root, true);
        assertThat(resource(root).status()).isEqualTo("RELEASED");
    }

    @Test
    void expiredReferencesCannotBeRevivedAndClaimsHaveTokenFencing() {
        var root = active();
        fixtures.expire(UUID.fromString(root.referenceId()));
        assertThatThrownBy(() -> guard.heartbeat(owner, root))
                .isInstanceOf(IllegalStateException.class);
        UUID token = UUID.randomUUID();
        var claimed =
                admin.execute(
                        tx ->
                                gc.claim(
                                        UUID.fromString(root.resourceId()),
                                        token,
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now().plusSeconds(60),
                                        3));
        assertThat(claimed).isPresent();
        java.util.Optional<SandboxUsageRepository.Resource> secondClaim =
                admin.execute(
                        tx ->
                                gc.claim(
                                        UUID.fromString(root.resourceId()),
                                        UUID.randomUUID(),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now().plusSeconds(60),
                                        3));
        assertThat(secondClaim).isEmpty();
        Boolean wrongToken =
                admin.execute(
                        tx ->
                                gc.finish(
                                        UUID.fromString(root.resourceId()),
                                        UUID.randomUUID(),
                                        true));
        assertThat(wrongToken).isFalse();
        Boolean finished =
                admin.execute(tx -> gc.finish(UUID.fromString(root.resourceId()), token, true));
        assertThat(finished).isTrue();
        assertThat(
                        repository
                                .reference(org, user, UUID.fromString(root.referenceId()))
                                .orElseThrow()
                                .status())
                .isEqualTo("EXPIRED");
    }

    @Test
    void revokedAllocationIsRegisteredEvenAfterEmptyReservationWasReaped() {
        var root = guard.reserve(owner, slot(owner));
        fixtures.expire(UUID.fromString(root.referenceId()));
        assertThat(
                        job((type, id) -> {
                                    throw new AssertionError("No known backend yet");
                                })
                                .collectOnce())
                .isEqualTo(1);
        assertThatThrownBy(() -> guard.activate(owner, root, sandbox))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resource(root).externalId()).isEqualTo(sandbox.getState().getSessionId());
        assertThat(resource(root).status()).isEqualTo("RELEASE_FAILED");
        assertThat(
                        job((type, id) -> SandboxBackendTerminator.TerminationResult.success())
                                .collectOnce())
                .isEqualTo(1);
    }

    @Test
    void gcRechecksReferencesAfterWaitingForAHeartbeatTransaction() throws Exception {
        var root = active();
        var child = context(run);
        var childRef = guard.borrow(child, root, sandbox);
        assertThat(guard.close(owner, root, false)).isFalse();
        fixtures.expire(UUID.fromString(childRef.referenceId()));
        var renewed = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var heartbeat =
                    executor.submit(
                            () -> {
                                TenantContextHolder.setOrgId(org.toString());
                                try {
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        fixtures.lockResource(
                                                                UUID.fromString(root.resourceId()));
                                                        // Emulate an in-flight renewal committed
                                                        // after the reaper's scan.
                                                        fixtures.renew(
                                                                UUID.fromString(
                                                                        childRef.referenceId()));
                                                        renewed.countDown();
                                                        try {
                                                            if (!commit.await(5, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "test renewal timeout");
                                                        } catch (InterruptedException error) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(error);
                                                        }
                                                    });
                                } finally {
                                    TenantContextHolder.clear();
                                }
                            });
            assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
            var claiming =
                    executor.submit(
                            () ->
                                    admin.execute(
                                            tx ->
                                                    gc.claim(
                                                            UUID.fromString(root.resourceId()),
                                                            UUID.randomUUID(),
                                                            OffsetDateTime.now(),
                                                            OffsetDateTime.now().plusSeconds(60),
                                                            3)));
            assertThatThrownBy(() -> claiming.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown();
            heartbeat.get(5, TimeUnit.SECONDS);
            assertThat(claiming.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(resource(root).status()).isEqualTo("DRAINING");
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void borrowAndOwnerCloseSerializeWithoutEarlyPhysicalRelease() throws Exception {
        var root = active();
        var child = context(run);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var borrowed =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    return freshGuard().borrow(child, root, sandbox);
                                } catch (IllegalStateException closing) {
                                    return null;
                                }
                            });
            var closed =
                    executor.submit(
                            () -> {
                                start.await();
                                return freshGuard().close(owner, root, false);
                            });
            start.countDown();
            var ref = borrowed.get(8, TimeUnit.SECONDS);
            boolean destroy = closed.get(8, TimeUnit.SECONDS);
            assertThat(destroy).isEqualTo(ref == null);
            if (ref != null) {
                freshGuard().requireCurrent(child, ref, sandbox);
                freshGuard().close(child, ref, false);
                assertThat(
                                job((type, id) ->
                                                SandboxBackendTerminator.TerminationResult
                                                        .success())
                                        .collectOnce())
                        .isEqualTo(1);
            } else guard.released(owner, root, true);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PgSandboxUsageGuard freshGuard() {
        return new PgSandboxUsageGuard(repository, runRepository, users, properties, manager);
    }

    private SandboxUsageGuard.Binding active() {
        var ref = guard.reserve(owner, slot(owner));
        guard.activate(owner, ref, sandbox);
        return ref;
    }

    private SandboxUsageRepository.Resource resource(SandboxUsageGuard.Binding ref) {
        return repository.find(org, user, UUID.fromString(ref.resourceId())).orElseThrow();
    }

    private SandboxUsageGcJob job(SandboxBackendTerminator terminator) {
        return new SandboxUsageGcJob(gc, admin, guard, terminator, properties);
    }

    private SandboxIsolationKey slot(RuntimeContext ctx) {
        return SandboxIsolationKey.resolve(IsolationScope.SESSION, ctx, "assistant").orElseThrow();
    }

    private RuntimeContext context(RunOrchestrationService.RunHandle handle) {
        return RuntimeContext.builder()
                .userId(user.toString())
                .sessionId(handle.runId().toString())
                .put(TenantContext.ATTR_KEY, tenant)
                .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                .put(SandboxRuntimeAttributes.ATTR_RUN_ID, handle.runId().toString())
                .put(SandboxRuntimeAttributes.ATTR_TASK_ID, handle.rootTaskId().toString())
                .put(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID, handle.rootAttemptId().toString())
                .put(SandboxRuntimeAttributes.ATTR_LEASE_OWNER, "direct:" + handle.runId())
                .build();
    }
}
