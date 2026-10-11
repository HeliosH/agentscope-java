/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Entry;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceManifestService {
    private final WorkspaceManifestRepository repository;
    private final SaasProperties properties;
    private io.agentscope.saas.app.orchestration.RunExecutionPolicyService executionPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExecutionPolicies(
            io.agentscope.saas.app.orchestration.RunExecutionPolicyService policies) {
        executionPolicies = policies;
    }

    public WorkspaceManifestService(
            WorkspaceManifestRepository repository, SaasProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public Manifest freeze(Scope scope, WorkspaceProjectionPlan plan) {
        var policy =
                executionPolicies == null
                        ? new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                                properties.getSandbox().getTransferMaxFiles(),
                                properties.getFileStore().getMaxFileBytes(),
                                properties.getSandbox().getTransferMaxBytes())
                        : executionPolicies.workspacePolicy(scope.orgId(), scope.runId());
        if (plan.entries().size() > policy.maxFiles()
                || plan.totalBytes() > policy.maxTotalBytes()
                || plan.entries().stream().anyMatch(e -> e.sizeBytes() > policy.maxFileBytes()))
            throw new IllegalStateException("WORKSPACE_MANIFEST_LIMIT");
        List<Entry> entries =
                plan.entries().stream()
                        .map(
                                e ->
                                        new Entry(
                                                normalize(e.sourcePath()),
                                                normalize(e.path()),
                                                e.sizeBytes(),
                                                e.sha256(),
                                                e.unchanged(),
                                                null))
                        .toList();
        var normalized = plan(entries, plan.scannedFiles(), plan.totalBytes());
        if (!repository.lockScope(scope, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
        var manifest =
                new Manifest(
                        UUID.randomUUID(),
                        scope,
                        repository.nextSequence(scope),
                        normalized.version(),
                        entries.size(),
                        plan.totalBytes(),
                        plan.scannedFiles(),
                        "DISCOVERED",
                        false,
                        OffsetDateTime.now());
        repository.create(manifest, entries);
        return manifest;
    }

    @Transactional
    public void scanCompleted(Manifest admitted, WorkspaceProjectionReport report) {
        report.verifyComplete();
        Scope s = admitted.scope();
        if (!repository.lockScope(s, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
        var current =
                repository
                        .lockCurrent(
                                s.orgId(),
                                s.runId(),
                                s.execution().taskId(),
                                s.execution().attemptId(),
                                admitted.id())
                        .orElseThrow(
                                () -> new IllegalStateException("WORKSPACE_MANIFEST_SUPERSEDED"));
        if (!current.scope().equals(s)
                || current.totalBytes() != report.transferredBytes()
                || current.scannedFiles() != report.scannedFiles()
                || repository.scanCompleted(
                                s, admitted.id(), report.projectedFiles(), report.unchangedFiles())
                        != 1) throw new IllegalStateException("WORKSPACE_MANIFEST_SCAN_MISMATCH");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyAndReady(
            UUID org,
            UUID run,
            UUID task,
            UUID attempt,
            Manifest admitted,
            List<FileCatalogService.FileRecord> files) {
        if (admitted == null) throw new IllegalStateException("WORKSPACE_MANIFEST_MISSING");
        Scope s = admitted.scope();
        if (!org.equals(s.orgId())
                || !run.equals(s.runId())
                || !task.equals(s.execution().taskId())
                || !attempt.equals(s.execution().attemptId()))
            throw new SessionExecutionRevokedException();
        if (!repository.lockScope(s, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
        Manifest current =
                repository
                        .lockCurrent(org, run, task, attempt, admitted.id())
                        .orElseThrow(
                                () -> new IllegalStateException("WORKSPACE_MANIFEST_SUPERSEDED"));
        if (!current.scope().equals(s)
                || !current.scanComplete()
                || !("SCANNED".equals(current.status()) || "READY".equals(current.status())))
            throw new IllegalStateException("WORKSPACE_MANIFEST_SCAN_INCOMPLETE");
        List<Entry> entries = repository.entries(org, admitted.id());
        var expected = plan(entries, current.scannedFiles(), current.totalBytes());
        if (entries.size() != current.fileCount()
                || !expected.version().equals(current.planHash())
                || !current.planHash().equals(admitted.planHash())
                || files.size() != entries.size())
            throw new IllegalStateException("WORKSPACE_MANIFEST_INVENTORY_MISMATCH");
        var receipts =
                repository.receipts(s, admitted.id()).stream()
                        .collect(Collectors.toMap(r -> r.logicalPath(), r -> r));
        var actual = files.stream().collect(Collectors.toMap(f -> f.logicalPath(), f -> f));
        for (Entry entry : entries) {
            var receipt = receipts.get(entry.logicalPath());
            var file = actual.get(entry.logicalPath());
            if (entry.publicationId() == null
                    || receipt == null
                    || file == null
                    || !Objects.equals(receipt.fileId(), file.fileId())
                    || !Objects.equals(receipt.versionId(), file.versionId())
                    || !Objects.equals(receipt.objectKey(), file.objectKey())
                    || !Objects.equals(receipt.backend(), file.storageBackend())
                    || !Objects.equals(receipt.sha256(), file.sha256())
                    || !Objects.equals(entry.sha256(), file.sha256())
                    || receipt.sizeBytes() != file.sizeBytes()
                    || entry.sizeBytes() != file.sizeBytes())
                throw new IllegalStateException("WORKSPACE_MANIFEST_RECEIPT_MISSING_OR_MISMATCHED");
        }
        if (receipts.size() != entries.size() || repository.ready(org, admitted.id()) != 1)
            throw new IllegalStateException("WORKSPACE_MANIFEST_NOT_READY");
    }

    private static WorkspaceProjectionPlan plan(List<Entry> entries, int scanned, long bytes) {
        return new WorkspaceProjectionPlan(
                entries.stream()
                        .map(
                                e ->
                                        new WorkspaceProjectionPlan.Entry(
                                                e.sourcePath(),
                                                e.logicalPath(),
                                                e.sizeBytes(),
                                                e.sha256(),
                                                e.unchanged()))
                        .toList(),
                scanned,
                bytes);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt>
            verifyArchived(
                    io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Job job,
                    io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Authority
                            authority,
                    io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository archives) {
        if (!archives.lockAuthority(job, authority, OffsetDateTime.now()))
            throw new SessionExecutionRevokedException();
        var current =
                repository
                        .lockCurrent(
                                job.orgId(),
                                job.runId(),
                                job.taskId(),
                                job.attemptId(),
                                job.manifestId())
                        .orElseThrow(
                                () -> new IllegalStateException("WORKSPACE_MANIFEST_SUPERSEDED"));
        if (!current.scope().equals(job.scope())
                || !current.scanComplete()
                || !("SCANNED".equals(current.status()) || "READY".equals(current.status())))
            throw new IllegalStateException("WORKSPACE_MANIFEST_SCAN_INCOMPLETE");
        var entries = repository.entries(job.orgId(), job.manifestId());
        if (entries.size() != current.fileCount()
                || !plan(entries, current.scannedFiles(), current.totalBytes())
                        .version()
                        .equals(current.planHash()))
            throw new IllegalStateException("WORKSPACE_MANIFEST_INVENTORY_MISMATCH");
        var receipts = repository.receipts(job.scope(), job.manifestId());
        var byPath = receipts.stream().collect(Collectors.toMap(r -> r.logicalPath(), r -> r));
        for (var e : entries) {
            var r = byPath.get(e.logicalPath());
            if (e.publicationId() == null
                    || r == null
                    || e.sizeBytes() != r.sizeBytes()
                    || !e.sha256().equals(r.sha256()))
                throw new IllegalStateException("WORKSPACE_MANIFEST_RECEIPT_MISSING_OR_MISMATCHED");
        }
        if (byPath.size() != entries.size() || repository.ready(job.orgId(), job.manifestId()) != 1)
            throw new IllegalStateException("WORKSPACE_MANIFEST_NOT_READY");
        return receipts;
    }

    private static String normalize(String path) {
        String value = path.trim().replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid workspace inventory path");
        return value;
    }
}
