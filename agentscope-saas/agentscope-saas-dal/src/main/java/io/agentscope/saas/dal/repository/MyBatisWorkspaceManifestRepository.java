/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceManifestMapper;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceManifestRepository implements WorkspaceManifestRepository {
    private final WorkspaceManifestMapper mapper;

    public MyBatisWorkspaceManifestRepository(WorkspaceManifestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean lockScope(Scope s, OffsetDateTime now) {
        return !mapper.lockSession(s).isEmpty()
                && !mapper.lockRun(s).isEmpty()
                && !mapper.lockAttempt(s, now).isEmpty();
    }

    @Override
    public long nextSequence(Scope s) {
        return mapper.nextSequence(s);
    }

    @Override
    public void create(Manifest m, List<Entry> entries) {
        if (mapper.insert(m) != 1) throw new IllegalStateException("Manifest admission failed");
        for (int i = 0; i < entries.size(); i += 100) {
            var batch = entries.subList(i, Math.min(entries.size(), i + 100));
            if (mapper.insertEntries(m, batch) != batch.size())
                throw new IllegalStateException("Manifest inventory not committed");
        }
    }

    @Override
    public Optional<Manifest> lockCurrent(UUID org, UUID run, UUID task, UUID attempt, UUID id) {
        return mapper.lockCurrent(org, run, task, attempt, id).stream()
                .findFirst()
                .map(MyBatisWorkspaceManifestRepository::manifest);
    }

    @Override
    public Optional<Scope> restoreScope(UUID org, UUID user, UUID attempt) {
        return mapper.restoreScope(org, user, attempt).stream()
                .findFirst()
                .map(
                        d ->
                                new Scope(
                                        d.orgId(),
                                        d.userId(),
                                        d.agentId(),
                                        d.sessionId(),
                                        d.sessionGeneration(),
                                        d.runId(),
                                        new Execution(
                                                d.taskId(),
                                                d.agentRunId(),
                                                d.attemptId(),
                                                d.leaseOwner())));
    }

    @Override
    public Optional<Manifest> findCheckpoint(
            UUID org, UUID user, UUID run, UUID task, UUID attempt, UUID id) {
        return mapper.findCheckpoint(org, user, run, task, attempt, id).stream()
                .findFirst()
                .map(MyBatisWorkspaceManifestRepository::manifest);
    }

    @Override
    public boolean hasAttemptManifests(UUID org, UUID user, UUID run, UUID task, UUID attempt) {
        return mapper.hasAttemptManifests(org, user, run, task, attempt);
    }

    private static Manifest manifest(
            io.agentscope.saas.dal.mybatis.tenant.WorkspaceManifestData d) {
        return new Manifest(
                d.id(),
                new Scope(
                        d.orgId(),
                        d.userId(),
                        d.agentId(),
                        d.sessionId(),
                        d.sessionGeneration(),
                        d.runId(),
                        new Execution(d.taskId(), d.agentRunId(), d.attemptId(), d.leaseOwner())),
                d.manifestSeq(),
                d.planHash(),
                d.fileCount(),
                d.totalBytes(),
                d.scannedFiles(),
                d.status(),
                d.scanComplete(),
                d.createdAt());
    }

    @Override
    public List<Entry> entries(UUID org, UUID id) {
        return mapper.entries(org, id);
    }

    @Override
    public List<ArtifactReceipt> receipts(Scope s, UUID id) {
        return mapper.receipts(s, id);
    }

    @Override
    public int recordPublication(UUID id, Publication p, Intent i) {
        return mapper.recordPublication(id, p, i);
    }

    @Override
    public int scanCompleted(Scope s, UUID id, int projected, int unchanged) {
        return mapper.scanCompleted(s, id, projected, unchanged);
    }

    @Override
    public int ready(UUID org, UUID id) {
        return mapper.ready(org, id);
    }
}
