/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ModelException;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy;
import io.agentscope.saas.app.chat.ChatRunStartService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicy;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicyRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:accepted-policy-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "saas.datasource.admin.url=jdbc:h2:mem:accepted-policy-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "saas.orchestration.outbox-enabled=false"
        })
@ActiveProfiles("local")
class RunExecutionPolicyIntegrationTest {
    @Autowired ChatRunStartService starter;
    @Autowired RunExecutionPolicyService policies;
    @Autowired RunExecutionPolicyRepository repository;
    @Autowired DurableTaskLeaseRepository leaseRepository;
    @Autowired DurableTaskLeaseService leases;
    @Autowired RunOrchestrationService runs;
    @Autowired RunRecoveryCoordinator recovery;
    @Autowired RunRecoveryStatusService recoveryStatus;
    @Autowired RecoveryCapability capability;
    @Autowired SaasProperties properties;
    @Autowired io.agentscope.saas.app.workspace.FileCatalogService catalog;
    @Autowired io.agentscope.saas.app.workspace.WorkspaceInputService inputs;
    @Autowired io.agentscope.saas.app.workspace.WorkspaceManifestService manifests;
    @Autowired io.agentscope.saas.domain.workspace.WorkspaceManifestRepository workspaceScopes;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    TransactionTemplate transactions;

    @Autowired
    @Qualifier("adminDataSource")
    DataSource source;

    TestDatabaseMapper database;
    TenantContext tenant;
    UUID org, user, agent, session;
    long oldFileLimit;
    int oldRecoveryAttempts;
    boolean oldRecoveryEnabled;

    @BeforeEach
    void seed() {
        transactions = new TransactionTemplate(manager);
        database = MyBatisRepositoryTestSupport.mapper(source, TestDatabaseMapper.class);
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        database.insertInvocationOrg(org, "policy-" + org);
        database.insertInvocationUser(user, org, user + "@policy.test");
        database.insertRuntimeArchiveAgent(agent, org, user);
        database.insertRuntimeArchiveSession(session, org, user, agent);
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        TenantContextHolder.setOrgId(org.toString());
        oldFileLimit = properties.getFileStore().getMaxFileBytes();
        oldRecoveryAttempts = properties.getModel().getStreamRecovery().getMaxAttempts();
        oldRecoveryEnabled = properties.getModel().getStreamRecovery().isEnabled();
    }

    @AfterEach
    void restore() {
        properties.getFileStore().setMaxFileBytes(oldFileLimit);
        properties.getModel().getStreamRecovery().setMaxAttempts(oldRecoveryAttempts);
        properties.getModel().getStreamRecovery().setEnabled(oldRecoveryEnabled);
        TenantContextHolder.clear();
    }

    ChatRunStartService.StartedRun start(String key) {
        return starter.start(
                tenant,
                agent.toString(),
                session.toString(),
                "Policy test",
                key,
                "Policy test",
                "default",
                List.of());
    }

    @Test
    void admissionAndIdempotencyKeepAnImmutableContractWhileNewRunsUseNewPolicy() {
        properties.getFileStore().setMaxFileBytes(8 * 1024 * 1024);
        var first = start("original");
        var accepted = repository.find(org, first.runId()).orElseThrow();
        properties.getFileStore().setMaxFileBytes(1024 * 1024);
        assertThat(start("original").runId()).isEqualTo(first.runId());
        assertThat(repository.find(org, first.runId()).orElseThrow()).isEqualTo(accepted);
        assertThat(policies.workspacePolicy(org, first.runId()).maxFileBytes())
                .isEqualTo(8 * 1024 * 1024);
        assertThat(repository.find(org, start("new-policy").runId()).orElseThrow().maxFileBytes())
                .isEqualTo(1024 * 1024);
        assertThatThrownBy(
                        () -> transactions.executeWithoutResult(tx -> repository.insert(accepted)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.find(UUID.randomUUID(), first.runId())).isEmpty();
    }

    @Test
    void contextBindingNeverMutatesTheWorkerDefaultAndModelRetryBudgetDoesNotChangeMidRun() {
        properties.getFileStore().setMaxFileBytes(8 * 1024 * 1024);
        properties.getModel().getStreamRecovery().setMaxAttempts(3);
        properties.getModel().getStreamRecovery().setEnabled(true);
        var run = start("context");
        properties.getFileStore().setMaxFileBytes(1024 * 1024);
        properties.getModel().getStreamRecovery().setMaxAttempts(1);
        properties.getModel().getStreamRecovery().setEnabled(false);
        var context = RuntimeContext.builder().build();
        policies.bind(context, org, run.runId());
        assertThat(context.get(WorkspaceTransferPolicy.class).maxFileBytes())
                .isEqualTo(8 * 1024 * 1024);
        assertThat(properties.getFileStore().getMaxFileBytes()).isEqualTo(1024 * 1024);
        var scope =
                new ModelInvocationRepository.Scope(
                        org,
                        user,
                        run.runId(),
                        run.rootTaskId(),
                        run.rootAgentRunId(),
                        run.rootAttemptId(),
                        "direct:" + run.runId());
        var decision = recovery.decide(new ModelException("interrupted"), 1, scope);
        assertThat(decision.recoverable()).isTrue();
        assertThat(decision.maxAttempts()).isEqualTo(3);
        var newRun = start("disabled-new");
        assertThat(
                        recovery.decide(
                                        new ModelException("interrupted"),
                                        1,
                                        new ModelInvocationRepository.Scope(
                                                org,
                                                user,
                                                newRun.runId(),
                                                newRun.rootTaskId(),
                                                newRun.rootAgentRunId(),
                                                newRun.rootAttemptId(),
                                                "direct:" + newRun.runId()))
                                .recoverable())
                .isFalse();
    }

    @Test
    void compatibleSelectionHappensBeforeLimitAndDoesNotConsumeAnIncompatibleRun()
            throws InterruptedException {
        var foreign = runs.createDirectRun(tenant, agent, session, null, "Foreign deployment");
        var template = repository.find(org, start("template").runId()).orElseThrow();
        var foreignPolicy =
                new RunExecutionPolicy(
                        org,
                        foreign.runId(),
                        1,
                        "other-deployment",
                        template.capabilityHash(),
                        template.maxFiles(),
                        template.maxFileBytes(),
                        template.maxWorkspaceBytes(),
                        template.recoveryEnabled(),
                        template.recoveryMode(),
                        template.recoveryMaxAttempts(),
                        template.recoveryInitialDelayMillis(),
                        template.recoveryMaxDelayMillis(),
                        OffsetDateTime.now());
        transactions.executeWithoutResult(tx -> repository.insert(foreignPolicy));
        String foreignOwner = "direct:" + foreign.runId();
        assertThat(leases.activateDirect(foreign.rootAttemptId(), foreignOwner)).isTrue();
        assertThat(
                        leases.recover(
                                foreign.rootAttemptId(),
                                foreignOwner,
                                "MODEL_REQUEST_FAILED",
                                "retry",
                                0))
                .isTrue();
        var compatible = start("compatible");
        String owner = "direct:" + compatible.runId();
        assertThat(leases.activateDirect(compatible.rootAttemptId(), owner)).isTrue();
        assertThat(
                        leases.recover(
                                compatible.rootAttemptId(),
                                owner,
                                "MODEL_REQUEST_FAILED",
                                "retry",
                                0))
                .isTrue();
        Thread.sleep(2200);
        var candidates =
                leaseRepository.findCompatibleReadyCandidates(
                        OffsetDateTime.now(),
                        1,
                        capability.deploymentScope(),
                        capability.capabilityHash(),
                        RunExecutionPolicyService.physicalArchiveLimit(properties),
                        RunExecutionPolicyService.physicalFileLimit(properties));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).runId()).isEqualTo(compatible.runId());
        assertThat(database.taskStatus(foreign.rootTaskId())).isEqualTo("READY");
        assertThat(leases.claimReady("policy-worker", 1).get(0).runId())
                .isEqualTo(compatible.runId());
    }

    @Test
    void physicalLimitsAndProtocolMismatchRejectBindingWithoutAlteringTheSnapshot() {
        var run = start("physical");
        var accepted = repository.find(org, run.runId()).orElseThrow();
        var restricted = new SaasProperties();
        restricted.getSandbox().setMaxSpoolBytes(1);
        var incompatible =
                new RunExecutionPolicyService(
                        repository, restricted, new RecoveryCapability(restricted, null));
        assertThatThrownBy(
                        () -> incompatible.bind(RuntimeContext.builder().build(), org, run.runId()))
                .hasMessage("RUN_POLICY_WORKER_INCOMPATIBLE");
        assertThat(repository.find(org, run.runId()).orElseThrow()).isEqualTo(accepted);
        assertThat(
                        leaseRepository.findCompatibleReadyCandidates(
                                OffsetDateTime.now(),
                                1,
                                capability.deploymentScope(),
                                "0".repeat(64),
                                1,
                                1))
                .isEmpty();
    }

    @Test
    void policyFailureRollsBackAdmissionRatherThanLeavingAnUnprotectedRun() {
        properties.getModel().getStreamRecovery().setMaxAttempts(0);
        assertThatThrownBy(() -> start("invalid-policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.findByIdempotencyKey(tenant, agent, "invalid-policy")).isEmpty();
    }

    @Test
    void missingAcceptedSnapshotIsNotLegacyAndCannotBeClaimedUsingCurrentDefaults() {
        var run = start("missing-snapshot");
        String owner = "direct:" + run.runId();
        assertThat(leases.activateDirect(run.rootAttemptId(), owner)).isTrue();
        assertThat(leases.recover(run.rootAttemptId(), owner, "MODEL_REQUEST_FAILED", "retry", 0))
                .isTrue();
        assertThat(database.deleteAcceptedPolicyForProbe(org, run.runId())).isEqualTo(1);
        assertThat(repository.requiresSnapshot(org, run.runId())).isTrue();
        assertThatThrownBy(() -> policies.workspacePolicy(org, run.runId()))
                .hasMessage("RUN_POLICY_SNAPSHOT_MISSING");
        assertThatThrownBy(() -> policies.bind(RuntimeContext.builder().build(), org, run.runId()))
                .hasMessage("RUN_POLICY_SNAPSHOT_MISSING");
        assertThat(
                        leaseRepository.findCompatibleReadyCandidates(
                                OffsetDateTime.now().plusMinutes(1),
                                10,
                                capability.deploymentScope(),
                                capability.capabilityHash(),
                                RunExecutionPolicyService.physicalArchiveLimit(properties),
                                RunExecutionPolicyService.physicalFileLimit(properties)))
                .noneMatch(candidate -> candidate.runId().equals(run.runId()));
        assertThat(recoveryStatus.status(tenant, agent, run.runId()).state())
                .isEqualTo("MANUAL_ACTION");
    }

    @Test
    void exactBoundInputsAndPublicationInventoryUseAcceptedLimitsAfterTheWriteLimitIsTightened() {
        properties.getFileStore().setMaxFileBytes(3);
        var file =
                catalog.recordWorkspaceFile(
                                tenant,
                                agent,
                                session,
                                "inputs/policy.txt",
                                new byte[] {1, 2, 3},
                                "application/octet-stream",
                                "test",
                                java.util.Map.of())
                        .orElseThrow();
        var run =
                starter.start(
                        tenant,
                        agent.toString(),
                        session.toString(),
                        "Use pinned file",
                        "pinned-policy",
                        "Use pinned file",
                        "default",
                        List.of(
                                new io.agentscope.saas.app.chat.ChatPersistenceService
                                        .AttachmentInput(file.logicalPath(), file.versionId())));
        properties.getFileStore().setMaxFileBytes(2);
        boolean enabled = properties.getSandbox().isEnabled();
        try {
            properties.getSandbox().setEnabled(true);
            var plan =
                    inputs.prepare(
                                    tenant,
                                    run.runId(),
                                    run.rootTaskId(),
                                    run.rootAttemptId(),
                                    "direct:" + run.runId())
                            .orElseThrow();
            assertThat(plan.workspace().files()).hasSize(1);
            assertThat(plan.workspace().files().get(0).content())
                    .containsExactly((byte) 1, (byte) 2, (byte) 3);
            var scope = workspaceScopes.restoreScope(org, user, run.rootAttemptId()).orElseThrow();
            var inventory =
                    new io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan(
                            List.of(
                                    new io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan
                                            .Entry(
                                            "outputs/accepted.txt",
                                            "outputs/accepted.txt",
                                            3,
                                            file.sha256(),
                                            false)),
                            1,
                            3);
            assertThat(manifests.freeze(scope, inventory).totalBytes()).isEqualTo(3);
            assertThatThrownBy(
                            () ->
                                    catalog.recordWorkspaceFile(
                                            tenant,
                                            agent,
                                            session,
                                            "inputs/rejected.txt",
                                            new byte[] {1, 2, 3},
                                            "application/octet-stream",
                                            "test",
                                            java.util.Map.of()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(catalog.describeVersion(tenant, file.versionId()).orElseThrow().sizeBytes())
                    .isEqualTo(3);
        } finally {
            properties.getSandbox().setEnabled(enabled);
        }
    }
}
