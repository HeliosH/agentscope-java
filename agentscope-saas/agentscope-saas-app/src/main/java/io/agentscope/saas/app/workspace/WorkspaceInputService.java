/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceInputPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.WorkspaceManifestVersion;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.WorkspaceInputRepository;
import io.agentscope.saas.domain.workspace.WorkspaceInputRepository.Input;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses existing durable attachment bindings; object reads never carry execution SQL locks. */
@Service
public class WorkspaceInputService {
    private final WorkspaceInputRepository inputs;
    private final WorkspaceManifestRepository scopes;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private io.agentscope.saas.app.orchestration.RunExecutionPolicyService executionPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExecutionPolicies(
            io.agentscope.saas.app.orchestration.RunExecutionPolicyService policies) {
        executionPolicies = policies;
    }

    private io.agentscope.harness.agent.sandbox.WorkspaceTransferResources transfers =
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources.DEFAULT;

    @org.springframework.beans.factory.annotation.Autowired
    void configureTransferResources(
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources resources) {
        transfers = resources;
    }

    public WorkspaceInputService(
            WorkspaceInputRepository inputs,
            WorkspaceManifestRepository scopes,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.inputs = inputs;
        this.scopes = scopes;
        this.stores = stores;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(10);
    }

    public Optional<WorkspaceInputPlan> prepare(
            TenantContext tenant, UUID run, UUID task, UUID attempt, String owner) {
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(tenant.orgId());
        try {
            return prepareOwned(tenant, run, task, attempt, owner);
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    private Optional<WorkspaceInputPlan> prepareOwned(
            TenantContext tenant, UUID run, UUID task, UUID attempt, String owner) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Input reads require transaction-free IO");
        if (!properties.getSandbox().isEnabled()) return Optional.empty();
        var prepared =
                transactions.execute(
                        tx -> {
                            var scope =
                                    scopes.restoreScope(
                                                    UUID.fromString(tenant.orgId()),
                                                    UUID.fromString(tenant.userId()),
                                                    attempt)
                                            .orElseThrow(SessionExecutionRevokedException::new);
                            if (!run.equals(scope.runId())
                                    || !task.equals(scope.execution().taskId())
                                    || !Objects.equals(owner, scope.execution().leaseOwner()))
                                throw new SessionExecutionRevokedException();
                            requireCurrent(scope);
                            return new Prepared(scope, bindings(scope));
                        });
        if (prepared.files().isEmpty()) return Optional.empty();
        FileObjectStore store =
                Objects.requireNonNull(stores.getIfAvailable(), "Input object store unavailable");
        List<WorkspaceRestorePlan.WorkspaceFile> files = new ArrayList<>();
        for (var input : prepared.files()) {
            if (!store.backend().equals(input.backend()))
                throw new IllegalStateException("WORKSPACE_INPUT_BACKEND_CHANGED");
            var descriptor =
                    new WorkspaceRestorePlan.WorkspaceFile(
                            input.logicalPath(),
                            input.sizeBytes(),
                            input.sha256(),
                            () -> {
                                validatePrepared(prepared);
                                var stream =
                                        store.openRead(
                                                prepared.scope().orgId(),
                                                input.objectKey(),
                                                input.sizeBytes());
                                return new java.io.FilterInputStream(stream) {
                                    @Override
                                    public void close() throws java.io.IOException {
                                        try {
                                            super.close();
                                        } finally {
                                            validatePrepared(prepared);
                                        }
                                    }
                                };
                            });
            try (var permit = transfers.acquire()) {
                descriptor.transferTo(
                        java.io.OutputStream.nullOutputStream(),
                        properties.getSandbox().getTransferBufferBytes());
            } catch (SessionExecutionRevokedException | InputBindingsChangedException revoked) {
                throw revoked;
            } catch (Exception error) {
                throw new IllegalStateException("WORKSPACE_INPUT_UNAVAILABLE", error);
            }
            files.add(descriptor);
        }
        transactions.executeWithoutResult(
                tx -> {
                    requireCurrent(prepared.scope());
                    if (!bindings(prepared.scope()).equals(prepared.files()))
                        throw new InputBindingsChangedException();
                    requireCurrent(prepared.scope());
                });
        String version =
                WorkspaceManifestVersion.compute(
                        prepared.files().stream()
                                .map(
                                        f ->
                                                new WorkspaceManifestVersion.Entry(
                                                        f.logicalPath(), f.versionId(), f.sha256()))
                                .toList());
        return Optional.of(
                new WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "workspace-inputs://runs/" + run + "/tasks/" + task,
                                version,
                                files)));
    }

    private List<Input> bindings(Scope scope) {
        var cfg = properties.getSandbox();
        var policy =
                executionPolicies == null
                        ? new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                                cfg.getTransferMaxFiles(),
                                properties.getFileStore().getMaxFileBytes(),
                                cfg.getTransferMaxBytes())
                        : executionPolicies.workspacePolicy(scope.orgId(), scope.runId());
        var raw = inputs.bindings(scope, Math.addExact(policy.maxFiles(), 1));
        if (raw.size() > policy.maxFiles())
            throw new IllegalStateException("WORKSPACE_INPUT_LIMIT");
        var byPath = new LinkedHashMap<String, Input>();
        long total = 0;
        for (var f : raw) {
            if (f.logicalPath() == null
                    || !(f.logicalPath().startsWith("inputs/")
                            || f.logicalPath().startsWith("outputs/"))
                    || !new WorkspaceRestorePlan.WorkspaceFile(f.logicalPath(), new byte[0])
                            .path()
                            .equals(f.logicalPath())
                    || f.backend() == null
                    || f.objectKey() == null
                    || f.objectKey().isBlank()
                    || f.sizeBytes() == null
                    || f.sizeBytes() < 0
                    || f.sha256() == null
                    || !f.sha256().matches("[a-f0-9]{64}"))
                throw new IllegalStateException("WORKSPACE_INPUT_BINDING_INVALID");
            var previous = byPath.putIfAbsent(f.logicalPath(), f);
            if (previous != null) {
                if (!previous.versionId().equals(f.versionId()))
                    throw new IllegalStateException("WORKSPACE_INPUT_PATH_CONFLICT");
                continue;
            }
            total = Math.addExact(total, f.sizeBytes());
            if (f.sizeBytes() > policy.maxFileBytes() || total > policy.maxTotalBytes())
                throw new IllegalStateException("WORKSPACE_INPUT_LIMIT");
        }
        return List.copyOf(byPath.values());
    }

    private void requireCurrent(Scope scope) {
        if (!scopes.lockScope(scope, OffsetDateTime.now())
                || !scopes.lockScope(scope, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
    }

    private void validatePrepared(Prepared prepared) {
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(
                prepared.scope().orgId().toString());
        try {
            transactions.executeWithoutResult(
                    tx -> {
                        requireCurrent(prepared.scope());
                        if (!bindings(prepared.scope()).equals(prepared.files()))
                            throw new InputBindingsChangedException();
                        requireCurrent(prepared.scope());
                    });
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    private record Prepared(Scope scope, List<Input> files) {}

    private static final class InputBindingsChangedException extends IllegalStateException {
        InputBindingsChangedException() {
            super("WORKSPACE_INPUT_BINDINGS_CHANGED");
        }
    }
}
