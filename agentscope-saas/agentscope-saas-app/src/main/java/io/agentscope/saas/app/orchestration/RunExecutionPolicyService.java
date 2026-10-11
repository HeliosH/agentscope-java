/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicy;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicyRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunExecutionPolicyService {
    private final RunExecutionPolicyRepository repository;
    private final SaasProperties properties;
    private final RecoveryCapability capability;

    public RunExecutionPolicyService(
            RunExecutionPolicyRepository repository,
            SaasProperties properties,
            RecoveryCapability capability) {
        this.repository = repository;
        this.properties = properties;
        this.capability = capability;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(UUID org, UUID run) {
        var workspace = currentWorkspacePolicy();
        var recovery = properties.getModel().getStreamRecovery();
        repository.insert(
                new RunExecutionPolicy(
                        org,
                        run,
                        1,
                        capability.deploymentScope(),
                        capability.capabilityHash(),
                        workspace.maxFiles(),
                        workspace.maxFileBytes(),
                        workspace.maxTotalBytes(),
                        recovery.isEnabled(),
                        recovery.getMode().name(),
                        recovery.getMaxAttempts(),
                        recovery.getInitialBackoffMillis(),
                        recovery.getMaxBackoffMillis(),
                        OffsetDateTime.now()));
    }

    public Optional<RunExecutionPolicy> find(UUID org, UUID run) {
        if (run == null) return Optional.empty();
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(org.toString());
        try {
            var accepted = repository.find(org, run);
            if (accepted.isEmpty() && repository.requiresSnapshot(org, run))
                throw new io.agentscope.saas.domain.orchestration
                        .RunExecutionPolicyUnavailableException();
            return accepted;
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    public WorkspaceTransferPolicy workspacePolicy(UUID org, UUID run) {
        return find(org, run)
                .map(
                        p ->
                                new WorkspaceTransferPolicy(
                                        p.maxFiles(), p.maxFileBytes(), p.maxWorkspaceBytes()))
                .orElseGet(this::currentWorkspacePolicy);
    }

    public void bind(RuntimeContext context, UUID org, UUID run) {
        find(org, run)
                .ifPresent(
                        p -> {
                            var policy =
                                    new WorkspaceTransferPolicy(
                                            p.maxFiles(), p.maxFileBytes(), p.maxWorkspaceBytes());
                            if (!p.deploymentScope().equals(capability.deploymentScope())
                                    || !p.capabilityHash().equals(capability.capabilityHash())
                                    || WorkspaceTransferResources.archiveLimit(policy)
                                            > physicalArchiveLimit(properties)
                                    || p.maxFileBytes() > physicalFileLimit(properties))
                                throw new IllegalStateException("RUN_POLICY_WORKER_INCOMPATIBLE");
                            context.put(RunExecutionPolicy.class, p);
                            context.put(WorkspaceTransferPolicy.class, policy);
                        });
    }

    public SaasProperties.ModelStreamRecovery recoveryPolicy(UUID org, UUID run) {
        return find(org, run)
                .map(
                        p -> {
                            var recovery = new SaasProperties.ModelStreamRecovery();
                            recovery.setEnabled(p.recoveryEnabled());
                            recovery.setMode(
                                    SaasProperties.ModelStreamRecovery.Mode.valueOf(
                                            p.recoveryMode()));
                            recovery.setMaxAttempts(p.recoveryMaxAttempts());
                            recovery.setInitialBackoffMillis(p.recoveryInitialDelayMillis());
                            recovery.setMaxBackoffMillis(p.recoveryMaxDelayMillis());
                            return recovery;
                        })
                .orElse(properties.getModel().getStreamRecovery());
    }

    private WorkspaceTransferPolicy currentWorkspacePolicy() {
        return new WorkspaceTransferPolicy(
                properties.getSandbox().getTransferMaxFiles(),
                properties.getFileStore().getMaxFileBytes(),
                properties.getSandbox().getTransferMaxBytes());
    }

    public static long physicalArchiveLimit(SaasProperties properties) {
        var sandbox = properties.getSandbox();
        long spool =
                sandbox.getMaxSpoolBytes()
                        / Math.addExact((long) sandbox.getMaxConcurrentTransfers(), 1L);
        return sandbox.isEnabled() && "opensandbox".equals(sandbox.getType())
                ? Math.min(spool, 536870912L)
                : spool;
    }

    public static long physicalFileLimit(SaasProperties properties) {
        return "pg".equals(properties.getFileStore().getBackend()) ? 33554432L : Long.MAX_VALUE;
    }
}
