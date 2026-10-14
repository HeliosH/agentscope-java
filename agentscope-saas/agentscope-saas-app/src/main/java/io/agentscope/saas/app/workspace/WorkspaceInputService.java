/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceInputPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceTaskScope;
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

/** Freezes task inputs across attempts; object reads never carry execution SQL locks. */
@Service
public class WorkspaceInputService {
    private final WorkspaceInputRepository inputs;
    private final WorkspaceManifestRepository scopes;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private io.agentscope.saas.app.orchestration.RunExecutionPolicyService executionPolicies;
    private io.agentscope.saas.domain.workspace.WorkspaceSystemRepository systems;
    private io.agentscope.saas.domain.orchestration.RunOrchestrationRepository events;

    @org.springframework.beans.factory.annotation.Autowired
    void configureEvents(
            io.agentscope.saas.domain.orchestration.RunOrchestrationRepository repository) {
        events = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureSystems(
            io.agentscope.saas.domain.workspace.WorkspaceSystemRepository repository) {
        systems = repository;
    }

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
        FileObjectStore store =
                prepared.files().isEmpty()
                        ? null
                        : Objects.requireNonNull(
                                stores.getIfAvailable(), "Input object store unavailable");
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
                                        WorkspaceObjectReads.open(
                                                store,
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
                    if (inputs.frozen(prepared.scope()).isEmpty()) {
                        inputs.freeze(
                                prepared.scope(),
                                new WorkspaceInputRepository.Frozen(
                                        version(prepared.files()),
                                        prepared.files().size(),
                                        prepared.files().stream()
                                                .mapToLong(Input::sizeBytes)
                                                .reduce(0L, Math::addExact)),
                                prepared.files());
                    }
                    requireCurrent(prepared.scope());
                });
        String version = version(prepared.files());
        return Optional.of(
                new WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "workspace-inputs://runs/" + run + "/tasks/" + task,
                                version,
                                files),
                        taskScope(prepared.scope())));
    }

    private WorkspaceTaskScope taskScope(Scope scope) {
        return scopes.isChildTask(scope)
                ? new WorkspaceTaskScope(scope.execution().taskId().toString())
                : null;
    }

    private List<Input> bindings(Scope scope) {
        var policy = policy(scope);
        var frozen = inputs.frozen(scope);
        List<Input> raw;
        if (frozen.isPresent())
            raw = inputs.frozenBindings(scope, Math.addExact(policy.maxFiles(), 1));
        else {
            raw = new ArrayList<>(inputs.bindings(scope, Math.addExact(policy.maxFiles(), 1)));
            if (raw.size() <= policy.maxFiles()) {
                for (var dependency :
                        inputs.dependencies(scope, policy.maxFiles() - raw.size() + 1)) {
                    if (dependency.sourceTaskId() == null || dependency.logicalPath() == null)
                        throw new IllegalStateException("WORKSPACE_INPUT_BINDING_INVALID");
                    String sourcePath =
                            new WorkspaceRestorePlan.WorkspaceFile(
                                            dependency.logicalPath(), new byte[0])
                                    .path();
                    if (!sourcePath.equals(dependency.logicalPath())
                            || !(sourcePath.startsWith("outputs/")
                                    || sourcePath.startsWith("inputs/")))
                        throw new IllegalStateException("WORKSPACE_INPUT_BINDING_INVALID");
                    raw.add(
                            new Input(
                                    null,
                                    dependency.fileId(),
                                    dependency.versionId(),
                                    "inputs/dependencies/"
                                            + dependency.sourceTaskId()
                                            + "/"
                                            + sourcePath,
                                    dependency.backend(),
                                    dependency.objectKey(),
                                    dependency.sizeBytes(),
                                    dependency.sha256(),
                                    dependency.sourceTaskId()));
                }
            }
        }
        if (frozen.isEmpty()) {
            for (var inherited : inputs.parentBindings(scope, Math.addExact(policy.maxFiles(), 1)))
                if (raw.stream()
                        .noneMatch(
                                existing ->
                                        existing.logicalPath().equals(inherited.logicalPath())
                                                && existing.versionId()
                                                        .equals(inherited.versionId())))
                    raw.add(inherited);
        }
        if (raw.size() > policy.maxFiles())
            throw new IllegalStateException("WORKSPACE_INPUT_LIMIT");
        var byPath = new LinkedHashMap<String, Input>();
        long total = 0;
        for (var f : raw) {
            if (f.fileId() == null
                    || f.versionId() == null
                    || f.logicalPath() == null
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
        var files =
                byPath.values().stream()
                        .sorted(java.util.Comparator.comparing(Input::logicalPath))
                        .toList();
        if (frozen.isPresent()
                && (frozen.get().fileCount() != files.size()
                        || frozen.get().totalBytes() != total
                        || !frozen.get().version().equals(version(files))))
            throw new IllegalStateException("WORKSPACE_INPUT_MANIFEST_INVALID");
        if (frozen.isPresent()) {
            for (var addition : inputs.additions(scope, Math.addExact(policy.maxFiles(), 1))) {
                if (addition.fileId() == null
                        || addition.versionId() == null
                        || addition.backend() == null
                        || addition.logicalPath() == null
                        || !addition.logicalPath().startsWith("inputs/retrieved/")
                        || !new WorkspaceRestorePlan.WorkspaceFile(
                                        addition.logicalPath(), new byte[0])
                                .path()
                                .equals(addition.logicalPath())
                        || addition.sizeBytes() == null
                        || addition.sizeBytes() < 0
                        || addition.sizeBytes() > policy.maxFileBytes()
                        || addition.sha256() == null
                        || !addition.sha256().matches("[a-f0-9]{64}")
                        || addition.objectKey() == null
                        || addition.objectKey().isBlank())
                    throw new IllegalStateException("WORKSPACE_INPUT_BINDING_INVALID");
                if (byPath.putIfAbsent(addition.logicalPath(), addition) != null)
                    throw new IllegalStateException("WORKSPACE_INPUT_PATH_CONFLICT");
                total = Math.addExact(total, addition.sizeBytes());
            }
            files =
                    byPath.values().stream()
                            .sorted(java.util.Comparator.comparing(Input::logicalPath))
                            .toList();
        }
        var system = systems == null ? null : systems.find(scope).orElse(null);
        if (files.size() > policy.maxFiles() - (system == null ? 0 : system.fileCount())
                || total > policy.maxTotalBytes() - (system == null ? 0 : system.totalBytes()))
            throw new IllegalStateException("WORKSPACE_INPUT_LIMIT");
        return files;
    }

    private io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy policy(Scope scope) {
        var cfg = properties.getSandbox();
        return executionPolicies == null
                ? new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                        cfg.getTransferMaxFiles(),
                        properties.getFileStore().getMaxFileBytes(),
                        cfg.getTransferMaxBytes())
                : executionPolicies.workspacePolicy(scope.orgId(), scope.runId());
    }

    public List<Input> search(Scope scope, String query, String afterPath, int limit) {
        if (query == null
                || query.length() > 512
                || afterPath == null
                || afterPath.length() > 1024
                || limit < 1
                || limit > 100) throw new IllegalArgumentException("Invalid file search window");
        return transactions.execute(
                tx -> {
                    requireCurrent(scope);
                    String pattern =
                            "%"
                                    + query.replace("!", "!!").replace("%", "!%").replace("_", "!_")
                                    + "%";
                    return inputs.search(scope, pattern, afterPath, limit + 1);
                });
    }

    /** Pin an explicitly selected historical version without changing the initial manifest. */
    public record Retrieved(WorkspaceInputPlan plan, String path) {}

    /** Previously loaded copies remain authorized only while their exact source versions are active. */
    public void requirePlanCurrent(io.agentscope.core.agent.RuntimeContext context) {
        var plan = context.get(WorkspaceInputPlan.class);
        if (plan == null) return;
        var current = executionScope(context);
        var uri = java.net.URI.create(plan.workspace().checkpointUri());
        String[] parts = uri.getPath().split("/");
        if (!"workspace-inputs".equals(uri.getScheme())
                || !"runs".equals(uri.getHost())
                || parts.length != 4
                || !current.runId().toString().equals(parts[1])
                || !"tasks".equals(parts[2])) throw new SessionExecutionRevokedException();
        var source =
                new Scope(
                        current.orgId(),
                        current.userId(),
                        current.agentId(),
                        current.sessionId(),
                        current.sessionGeneration(),
                        current.runId(),
                        new io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution(
                                UUID.fromString(parts[3]),
                                current.execution().agentRunId(),
                                current.execution().attemptId(),
                                current.execution().leaseOwner()));
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(current.orgId().toString());
        try {
            transactions.executeWithoutResult(
                    tx -> {
                        requireCurrent(current);
                        if (inputs.frozen(source).isEmpty())
                            throw new SessionExecutionRevokedException();
                        var authorized =
                                bindings(source).stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        Input::logicalPath, f -> f));
                        for (var file : plan.workspace().files()) {
                            var binding = authorized.get(file.path());
                            if (binding == null
                                    || file.size() != binding.sizeBytes()
                                    || !file.sha256().equals(binding.sha256()))
                                throw new SessionExecutionRevokedException();
                        }
                        requireCurrent(current);
                    });
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    public Scope executionScope(io.agentscope.core.agent.RuntimeContext context) {
        var tenant = Objects.requireNonNull(TenantContext.from(context));
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(tenant.orgId());
        try {
            return transactions.execute(
                    tx -> {
                        var s =
                                scopes.restoreScope(
                                                UUID.fromString(tenant.orgId()),
                                                UUID.fromString(tenant.userId()),
                                                UUID.fromString(
                                                        Objects.toString(
                                                                context.get(
                                                                        io.agentscope.saas.sandbox
                                                                                .SandboxRuntimeAttributes
                                                                                .ATTR_ATTEMPT_ID))))
                                        .orElseThrow(SessionExecutionRevokedException::new);
                        if (!s.execution()
                                        .leaseOwner()
                                        .equals(
                                                context.get(
                                                        io.agentscope.saas.sandbox
                                                                .SandboxRuntimeAttributes
                                                                .ATTR_LEASE_OWNER))
                                || !s.execution()
                                        .taskId()
                                        .toString()
                                        .equals(
                                                context.get(
                                                        io.agentscope.saas.sandbox
                                                                .SandboxRuntimeAttributes
                                                                .ATTR_TASK_ID))
                                || !s.runId()
                                        .toString()
                                        .equals(
                                                context.get(
                                                        io.agentscope.saas.orchestration
                                                                .RunOrchestrationService
                                                                .ATTR_RUN_ID)))
                            throw new SessionExecutionRevokedException();
                        requireCurrent(s);
                        return s;
                    });
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    public Retrieved retrieve(
            TenantContext tenant,
            UUID run,
            UUID task,
            UUID attempt,
            String owner,
            UUID fileId,
            UUID versionId)
            throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Input retrieval requires transaction-free IO");
        String prior = io.agentscope.saas.core.tenant.TenantContextHolder.getOrgId();
        io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(tenant.orgId());
        try {
            var scope =
                    transactions.execute(
                            tx -> {
                                var s =
                                        scopes.restoreScope(
                                                        UUID.fromString(tenant.orgId()),
                                                        UUID.fromString(tenant.userId()),
                                                        attempt)
                                                .orElseThrow(SessionExecutionRevokedException::new);
                                if (!run.equals(s.runId())
                                        || !task.equals(s.execution().taskId())
                                        || !owner.equals(s.execution().leaseOwner()))
                                    throw new SessionExecutionRevokedException();
                                requireCurrent(s);
                                if (inputs.frozen(s).isEmpty())
                                    throw new IllegalStateException(
                                            "WORKSPACE_INPUT_MANIFEST_MISSING");
                                return s;
                            });
            var selected =
                    transactions.execute(
                            tx -> {
                                requireCurrent(scope);
                                var current = bindings(scope);
                                var existing =
                                        current.stream()
                                                .filter(
                                                        f ->
                                                                fileId.equals(f.fileId())
                                                                        && versionId.equals(
                                                                                f.versionId()))
                                                .findFirst();
                                if (existing.isPresent()) return existing.get();
                                var raw =
                                        inputs.ownedVersion(scope, fileId, versionId)
                                                .orElseThrow(SessionExecutionRevokedException::new);
                                if (raw.logicalPath() == null
                                        || !(raw.logicalPath().startsWith("inputs/")
                                                || raw.logicalPath().startsWith("outputs/")))
                                    throw new SessionExecutionRevokedException();
                                String normalized =
                                        new WorkspaceRestorePlan.WorkspaceFile(
                                                        raw.logicalPath(), new byte[0])
                                                .path();
                                if (!normalized.equals(raw.logicalPath()))
                                    throw new SessionExecutionRevokedException();
                                String path =
                                        "inputs/retrieved/"
                                                + fileId
                                                + "/"
                                                + versionId
                                                + "/"
                                                + normalized.substring(
                                                        normalized.lastIndexOf('/') + 1);
                                var accepted = policy(scope);
                                var system =
                                        systems == null ? null : systems.find(scope).orElse(null);
                                long count =
                                        current.size() + (system == null ? 0 : system.fileCount());
                                long bytes =
                                        current.stream()
                                                        .mapToLong(f -> f.sizeBytes())
                                                        .reduce(0L, Math::addExact)
                                                + (system == null ? 0 : system.totalBytes());
                                if (raw.sizeBytes() == null
                                        || raw.sizeBytes() < 0
                                        || raw.sizeBytes() > accepted.maxFileBytes()
                                        || count >= accepted.maxFiles()
                                        || raw.sizeBytes() > accepted.maxTotalBytes() - bytes)
                                    throw new IllegalStateException("WORKSPACE_INPUT_LIMIT");
                                return new Input(
                                        null,
                                        fileId,
                                        versionId,
                                        path,
                                        raw.backend(),
                                        raw.objectKey(),
                                        raw.sizeBytes(),
                                        raw.sha256(),
                                        null);
                            });
            var store =
                    Objects.requireNonNull(
                            stores.getIfAvailable(), "Input object store unavailable");
            if (!store.backend().equals(selected.backend()))
                throw new IllegalStateException("WORKSPACE_INPUT_BACKEND_CHANGED");
            // Verify only the requested object, then pin it in a short CAS transaction.
            var selectedFile =
                    new WorkspaceRestorePlan.WorkspaceFile(
                            selected.logicalPath(),
                            selected.sizeBytes(),
                            selected.sha256(),
                            () ->
                                    WorkspaceObjectReads.open(
                                            store,
                                            scope.orgId(),
                                            selected.objectKey(),
                                            selected.sizeBytes()));
            try (var permit = transfers.acquire()) {
                selectedFile.transferTo(
                        java.io.OutputStream.nullOutputStream(), transfers.bufferBytes());
            }
            var prepared =
                    transactions.execute(
                            tx -> {
                                requireCurrent(scope);
                                var current = bindings(scope);
                                boolean added =
                                        current.stream()
                                                .noneMatch(
                                                        f ->
                                                                selected.versionId()
                                                                        .equals(f.versionId()));
                                if (added) {
                                    var active =
                                            inputs.ownedVersion(scope, fileId, versionId)
                                                    .orElseThrow(
                                                            SessionExecutionRevokedException::new);
                                    if (!Objects.equals(active.backend(), selected.backend())
                                            || !Objects.equals(
                                                    active.objectKey(), selected.objectKey())
                                            || !Objects.equals(
                                                    active.sizeBytes(), selected.sizeBytes())
                                            || !Objects.equals(active.sha256(), selected.sha256()))
                                        throw new SessionExecutionRevokedException();
                                    inputs.append(scope, selected);
                                }
                                var files =
                                        bindings(scope); // Budget rejection rolls back the append.
                                if (added && events != null)
                                    events.insertOutbox(
                                            new io.agentscope.saas.domain.orchestration
                                                    .RunOrchestrationRepository.NewOutboxMessage(
                                                    UUID.randomUUID(),
                                                    scope.orgId(),
                                                    scope.runId(),
                                                    "assistant_run",
                                                    "workspace_input_added",
                                                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                                                            .toJson(
                                                                    java.util.Map.of(
                                                                            "runId",
                                                                            scope.runId(),
                                                                            "taskId",
                                                                            scope.execution()
                                                                                    .taskId(),
                                                                            "fileId",
                                                                            fileId,
                                                                            "versionId",
                                                                            versionId,
                                                                            "path",
                                                                            selected.logicalPath(),
                                                                            "workspaceVersion",
                                                                            version(files)))));
                                requireCurrent(scope);
                                return new Prepared(scope, files);
                            });
            var descriptors = new ArrayList<WorkspaceRestorePlan.WorkspaceFile>();
            for (var f : prepared.files())
                descriptors.add(
                        new WorkspaceRestorePlan.WorkspaceFile(
                                f.logicalPath(),
                                f.sizeBytes(),
                                f.sha256(),
                                () -> {
                                    validatePrepared(prepared);
                                    return new java.io.FilterInputStream(
                                            WorkspaceObjectReads.open(
                                                    store,
                                                    scope.orgId(),
                                                    f.objectKey(),
                                                    f.sizeBytes())) {
                                        @Override
                                        public void close() throws java.io.IOException {
                                            try {
                                                super.close();
                                            } finally {
                                                validatePrepared(prepared);
                                            }
                                        }
                                    };
                                }));
            return new Retrieved(
                    new WorkspaceInputPlan(
                            new WorkspaceRestorePlan(
                                    "workspace-inputs://runs/" + run + "/tasks/" + task,
                                    version(prepared.files()),
                                    descriptors),
                            taskScope(scope)),
                    prepared.files().stream()
                            .filter(
                                    f ->
                                            fileId.equals(f.fileId())
                                                    && versionId.equals(f.versionId()))
                            .findFirst()
                            .orElseThrow()
                            .logicalPath());
        } finally {
            if (prior == null) io.agentscope.saas.core.tenant.TenantContextHolder.clear();
            else io.agentscope.saas.core.tenant.TenantContextHolder.setOrgId(prior);
        }
    }

    private static String version(List<Input> files) {
        return WorkspaceManifestVersion.compute(
                files.stream()
                        .map(
                                f ->
                                        new WorkspaceManifestVersion.Entry(
                                                f.logicalPath(), f.versionId(), f.sha256()))
                        .toList());
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
