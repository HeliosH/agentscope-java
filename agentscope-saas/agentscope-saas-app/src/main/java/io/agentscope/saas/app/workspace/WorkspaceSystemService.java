/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceSystemSnapshotStore;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.WorkspaceManifestVersion;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository.Entry;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository.Manifest;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable skills/memory projections with fenced, recoverable object staging. */
@Service
public class WorkspaceSystemService implements WorkspaceSystemSnapshotStore {
    private final WorkspaceSystemRepository systems;
    private final WorkspaceManifestRepository scopes;
    private final FilePublicationRepository publications;
    private final OrgRepository orgs;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;
    private final WorkspaceTransferResources transfers;
    private final TransactionTemplate tx;

    public WorkspaceSystemService(
            WorkspaceSystemRepository systems,
            WorkspaceManifestRepository scopes,
            FilePublicationRepository publications,
            OrgRepository orgs,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties,
            WorkspaceTransferResources transfers,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.systems = systems;
        this.scopes = scopes;
        this.publications = publications;
        this.orgs = orgs;
        this.stores = stores;
        this.properties = properties;
        this.transfers = transfers;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    @Override
    public WorkspaceRestorePlan prepare(
            RuntimeContext context, Source source, WorkspaceTransferPolicy policy)
            throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("System snapshots require transaction-free IO");
        var tenant =
                Objects.requireNonNull(
                        TenantContext.from(context), "System snapshot owner missing");
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(tenant.orgId());
        try {
            var scope =
                    tx.execute(
                            ignored -> {
                                var s =
                                        scopes.restoreScope(
                                                        UUID.fromString(tenant.orgId()),
                                                        UUID.fromString(tenant.userId()),
                                                        UUID.fromString(
                                                                Objects.toString(
                                                                        context.get(
                                                                                SandboxRuntimeAttributes
                                                                                        .ATTR_ATTEMPT_ID))))
                                                .orElseThrow(SessionExecutionRevokedException::new);
                                if (!s.execution()
                                                .taskId()
                                                .toString()
                                                .equals(
                                                        Objects.toString(
                                                                context.get(
                                                                        SandboxRuntimeAttributes
                                                                                .ATTR_TASK_ID)))
                                        || !s.execution()
                                                .leaseOwner()
                                                .equals(
                                                        context.get(
                                                                SandboxRuntimeAttributes
                                                                        .ATTR_LEASE_OWNER)))
                                    throw new SessionExecutionRevokedException();
                                requireCurrent(s);
                                return s;
                            });
            var input = context.get(io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class);
            int inputCount = input == null ? 0 : input.workspace().files().size();
            long inputBytes =
                    input == null
                            ? 0
                            : input.workspace().files().stream()
                                    .mapToLong(WorkspaceRestorePlan.WorkspaceFile::size)
                                    .reduce(0L, Math::addExact);
            if (systems.find(scope).isEmpty())
                capture(scope, source, policy, inputCount, inputBytes);
            var manifest = systems.find(scope).orElseThrow();
            var entries = systems.entries(scope, Math.addExact(policy.maxFiles(), 1));
            validateManifest(manifest, entries, policy);
            if (manifest.fileCount() > policy.maxFiles() - inputCount
                    || manifest.totalBytes() > policy.maxTotalBytes() - inputBytes)
                throw new IllegalStateException("SYSTEM_SNAPSHOT_LIMIT");
            var store =
                    entries.isEmpty()
                            ? null
                            : Objects.requireNonNull(
                                    stores.getIfAvailable(), "System object store unavailable");
            var files = new ArrayList<WorkspaceRestorePlan.WorkspaceFile>();
            for (var entry : entries) {
                if (!entry.backend().equals(store.backend()))
                    throw new IllegalStateException("SYSTEM_SNAPSHOT_BACKEND_CHANGED");
                files.add(
                        new WorkspaceRestorePlan.WorkspaceFile(
                                entry.path(),
                                entry.sizeBytes(),
                                entry.sha256(),
                                () -> {
                                    validateRead(scope, manifest, entry);
                                    return new java.io.FilterInputStream(
                                            WorkspaceObjectReads.open(
                                                    store,
                                                    scope.orgId(),
                                                    entry.objectKey(),
                                                    entry.sizeBytes())) {
                                        @Override
                                        public void close() throws java.io.IOException {
                                            try {
                                                super.close();
                                            } finally {
                                                validateRead(scope, manifest, entry);
                                            }
                                        }
                                    };
                                }));
            }
            return new WorkspaceRestorePlan(
                    "workspace-system://runs/" + scope.runId(), manifest.version(), files);
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    private void capture(
            Scope scope,
            Source source,
            WorkspaceTransferPolicy policy,
            int inputCount,
            long inputBytes)
            throws Exception {
        var paths = source.paths(policy.maxFiles()).stream().sorted().toList();
        if (paths.size() > policy.maxFiles() - inputCount
                || paths.stream().distinct().count() != paths.size())
            throw new IllegalStateException("SYSTEM_SNAPSHOT_LIMIT");
        var entries = new ArrayList<Entry>();
        var sourceKeys = new java.util.HashMap<String, String>();
        long total = 0;
        for (String path : paths) {
            var file = source.read(path, policy.maxFileBytes());
            requireSystemPath(file.path());
            if (sourceKeys.putIfAbsent(file.path(), path) != null)
                throw new IllegalStateException("SYSTEM_SNAPSHOT_PATH_CONFLICT");
            total = Math.addExact(total, file.size());
            if (file.size() > policy.maxFileBytes() || total > policy.maxTotalBytes() - inputBytes)
                throw new IllegalStateException("SYSTEM_SNAPSHOT_LIMIT");
            var store =
                    Objects.requireNonNull(
                            stores.getIfAvailable(), "System object store unavailable");
            var p =
                    tx.execute(
                            ignored -> {
                                orgs.lockTenantOrg(scope.orgId()).orElseThrow();
                                requireCurrent(scope);
                                long limit =
                                        properties.getFileStore().getWorkspaceSystemMaxOrgBytes();
                                if (file.size()
                                        > limit
                                                - systems.occupied(
                                                        scope.orgId(), OffsetDateTime.now()))
                                    throw new IllegalStateException("SYSTEM_SNAPSHOT_ORG_QUOTA");
                                UUID id = UUID.randomUUID();
                                String prefix = properties.getFileStore().getObjectKeyPrefix();
                                if (prefix == null || prefix.isBlank()) prefix = "files/";
                                if (!prefix.endsWith("/")) prefix += "/";
                                String key =
                                        prefix
                                                + "org="
                                                + scope.orgId()
                                                + "/user="
                                                + scope.userId()
                                                + "/publications/"
                                                + id
                                                + "-"
                                                + file.sha256().substring(0, 16);
                                var until =
                                        OffsetDateTime.now()
                                                .plusSeconds(
                                                        properties
                                                                .getFileStore()
                                                                .getPublicationLeaseSeconds());
                                var value =
                                        new Publication(
                                                id,
                                                scope.orgId(),
                                                scope.userId(),
                                                scope.agentId(),
                                                scope.sessionId(),
                                                scope.runId(),
                                                scope.sessionGeneration(),
                                                "/.agentscope/system-snapshots/"
                                                        + scope.runId()
                                                        + "/"
                                                        + id
                                                        + "/"
                                                        + file.path(),
                                                key,
                                                store.backend(),
                                                file.sha256(),
                                                file.size(),
                                                file.size(),
                                                true,
                                                "STAGED",
                                                until,
                                                until,
                                                null,
                                                0,
                                                null);
                                publications.stage(value);
                                return value;
                            });
            try (var permit = transfers.acquire();
                    var spool = transfers.spool("system-snapshot-", Math.max(1, file.size()))) {
                try (var out = spool.output()) {
                    file.transferTo(out, transfers.bufferBytes());
                }
                store.putFromPath(
                        scope.orgId(),
                        p.objectKey(),
                        spool.path(),
                        "application/octet-stream",
                        file.sha256());
            }
            tx.executeWithoutResult(
                    ignored -> {
                        requireCurrent(scope);
                        if (publications.stored(
                                        scope.orgId(), scope.userId(), p.id(), OffsetDateTime.now())
                                != 1) throw new IllegalStateException("SYSTEM_SNAPSHOT_EXPIRED");
                    });
            entries.add(
                    new Entry(
                            file.path(),
                            p.id(),
                            p.backend(),
                            p.objectKey(),
                            file.size(),
                            file.sha256()));
        }
        // A changing directory must not produce a mixed revision. No SQL lock spans these reads.
        if (!paths.equals(source.paths(policy.maxFiles()).stream().sorted().toList()))
            throw new IllegalStateException("SYSTEM_SNAPSHOT_SOURCE_CHANGED");
        for (var entry : entries) {
            var current = source.read(sourceKeys.get(entry.path()), policy.maxFileBytes());
            if (current.size() != entry.sizeBytes() || !current.sha256().equals(entry.sha256()))
                throw new IllegalStateException("SYSTEM_SNAPSHOT_SOURCE_CHANGED");
        }
        var frozen = new Manifest(version(entries), entries.size(), total);
        tx.executeWithoutResult(
                ignored -> {
                    orgs.lockTenantOrg(scope.orgId()).orElseThrow();
                    requireCurrent(scope);
                    // Parallel children may capture the same Run; the first committed manifest
                    // wins.
                    if (systems.find(scope).isPresent()) return;
                    for (var entry : entries) {
                        var p =
                                publications
                                        .lockOwned(
                                                scope.orgId(),
                                                scope.userId(),
                                                entry.publicationId())
                                        .orElseThrow();
                        if (!"STORED".equals(p.status())
                                || p.claimToken() != null
                                || !p.leaseUntil().isAfter(OffsetDateTime.now()))
                            throw new IllegalStateException("SYSTEM_SNAPSHOT_EXPIRED");
                    }
                    systems.freeze(scope, frozen, entries);
                    requireCurrent(scope);
                });
    }

    private void validateRead(Scope scope, Manifest manifest, Entry entry) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(scope.orgId().toString());
        try {
            tx.executeWithoutResult(
                    ignored -> {
                        requireCurrent(scope);
                        if (!systems.find(scope).filter(manifest::equals).isPresent())
                            throw new SessionExecutionRevokedException();
                        var p =
                                publications
                                        .lockOwned(
                                                scope.orgId(),
                                                scope.userId(),
                                                entry.publicationId())
                                        .orElseThrow();
                        if (!"STORED".equals(p.status())
                                || p.claimToken() != null
                                || !entry.objectKey().equals(p.objectKey()))
                            throw new IllegalStateException("SYSTEM_SNAPSHOT_UNAVAILABLE");
                    });
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    private void requireCurrent(Scope scope) {
        if (!scopes.lockScope(scope, OffsetDateTime.now())
                || !scopes.lockScope(scope, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
    }

    private static void validateManifest(
            Manifest manifest, List<Entry> entries, WorkspaceTransferPolicy policy) {
        long total = 0;
        for (var entry : entries) {
            requireSystemPath(entry.path());
            if (entry.sizeBytes() < 0 || entry.sizeBytes() > policy.maxFileBytes())
                throw new IllegalStateException("SYSTEM_SNAPSHOT_LIMIT");
            total = Math.addExact(total, entry.sizeBytes());
        }
        if (entries.size() > policy.maxFiles()
                || total > policy.maxTotalBytes()
                || manifest.fileCount() != entries.size()
                || manifest.totalBytes() != total
                || !version(entries).equals(manifest.version()))
            throw new IllegalStateException("SYSTEM_SNAPSHOT_MANIFEST_INVALID");
    }

    private static void requireSystemPath(String path) {
        if (!(path.equals("AGENTS.md")
                        || path.equals("MEMORY.md")
                        || path.equals("tools.json")
                        || path.startsWith("skills/")
                        || path.startsWith("subagents/")
                        || path.startsWith("memory/"))
                || !new WorkspaceRestorePlan.WorkspaceFile(path, new byte[0]).path().equals(path))
            throw new IllegalStateException("SYSTEM_SNAPSHOT_PATH_INVALID");
    }

    private static String version(List<Entry> entries) {
        return WorkspaceManifestVersion.compute(
                entries.stream()
                        .map(
                                e ->
                                        new WorkspaceManifestVersion.Entry(
                                                e.path(), e.publicationId(), e.sha256()))
                        .toList());
    }
}
