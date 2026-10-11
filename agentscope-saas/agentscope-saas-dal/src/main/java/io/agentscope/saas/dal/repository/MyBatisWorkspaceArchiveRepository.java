/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.WorkspaceArchiveCompletionMapper;
import io.agentscope.saas.dal.mybatis.tenant.WorkspaceArchiveMapper;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceArchiveRepository implements WorkspaceArchiveRepository {
    private final WorkspaceArchiveMapper tenant;
    private final WorkspaceArchiveCompletionMapper admin;

    public MyBatisWorkspaceArchiveRepository(
            WorkspaceArchiveMapper tenant, WorkspaceArchiveCompletionMapper admin) {
        this.tenant = tenant;
        this.admin = admin;
    }

    @Override
    public List<Publication> materializedCandidates(Scope scope) {
        return tenant.materializedCandidates(scope);
    }

    @Override
    public void create(Job job, List<Item> items) {
        createResult(job, items, null, false);
    }

    public void createInline(Job job, List<Item> items, String resultJson) {
        if (job.bodyId() != null
                || resultJson == null
                || resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("WORKSPACE_ARCHIVE_INLINE_LIMIT");
        createResult(job, items, resultJson, false);
    }

    public void createPrepared(Job job, List<Item> items, String resultJson) {
        if (job.bodyId() != null
                || resultJson == null
                || resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("WORKSPACE_ARCHIVE_INLINE_LIMIT");
        createResult(job, items, resultJson, true);
    }

    public boolean isPrepared(Job job) {
        return tenant.isPrepared(job);
    }

    public Optional<Job> findAttempt(Scope scope) {
        return tenant.findAttempt(scope).stream().findFirst();
    }

    public int settlePrepared(Job job, OffsetDateTime now) {
        return tenant.settlePrepared(job, now);
    }

    public int bindPublishedItems(Job job, OffsetDateTime now) {
        return tenant.bindPublishedItems(job, now);
    }

    public boolean lockPreparedScope(Job job) {
        return !tenant.lockSession(job).isEmpty()
                && !tenant.lockRun(job).isEmpty()
                && !tenant.lockPreparedAttempt(job).isEmpty();
    }

    public boolean activatePrepared(Job job, OffsetDateTime now) {
        if (tenant.activatePreparedAttempt(job, now) != 1) return false;
        if (tenant.transitionTask(job, now) != 1
                || tenant.transitionAgent(job, now) != 1
                || tenant.activatePrepared(job, now) != 1)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_SCOPE_CHANGED");
        return true;
    }

    public Optional<String> inlineResult(Job job) {
        return tenant.inlineResult(job).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public long inlineBytes(UUID orgId, UUID userId) {
        return tenant.inlineBytes(orgId, userId);
    }

    public int purgeInline(OffsetDateTime before, int limit) {
        return admin.purgeInline(before, limit);
    }

    private void createResult(Job job, List<Item> items, String resultJson, boolean prepared) {
        if (tenant.create(job, resultJson, prepared) != 1)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_NOT_CREATED");
        for (int start = 0; start < items.size(); start += 100) {
            var chunk = items.subList(start, Math.min(start + 100, items.size()));
            if (tenant.createItems(job, chunk) != chunk.size())
                throw new IllegalStateException("WORKSPACE_ARCHIVE_ITEMS_NOT_CREATED");
        }
    }

    @Override
    public boolean transition(Job job, OffsetDateTime now) {
        if (tenant.transitionAttempt(job, now) != 1) return false;
        tenant.bindLegacyOwner(job);
        if (tenant.transitionTask(job, now) != 1 || tenant.transitionAgent(job, now) != 1)
            throw new IllegalStateException("WORKSPACE_ARCHIVE_SCOPE_CHANGED");
        return true;
    }

    @Override
    public List<Job> candidates(OffsetDateTime now, int limit) {
        return admin.candidates(now, limit);
    }

    @Override
    public boolean lockScope(Job job) {
        return !tenant.lockSession(job).isEmpty()
                && !tenant.lockRun(job).isEmpty()
                && !tenant.lockAttempt(job).isEmpty();
    }

    @Override
    public Optional<Job> lockOwned(UUID org, UUID id) {
        return tenant.lockOwned(org, id).stream().findFirst();
    }

    @Override
    public int claim(
            Job job, UUID token, OffsetDateTime now, OffsetDateTime until, int maxAttempts) {
        return tenant.claim(job, token, now, until, maxAttempts);
    }

    @Override
    public boolean lockAuthority(Job job, Authority authority, OffsetDateTime now) {
        if (!job.id().equals(authority.jobId()) || !lockScope(job)) return false;
        return lockOwned(job.orgId(), job.id())
                .filter(
                        j ->
                                j.scope().equals(job.scope())
                                        && j.manifestId().equals(job.manifestId())
                                        && java.util.Objects.equals(j.bodyId(), job.bodyId())
                                        && "RUNNING".equals(j.status())
                                        && authority.token().equals(j.token())
                                        && j.leaseUntil().isAfter(now)
                                        && j.deadlineAt().isAfter(now))
                .isPresent();
    }

    @Override
    public List<Publication> publications(Job job) {
        return tenant.publications(job);
    }

    public List<Item> items(Job job) {
        return tenant.items(job);
    }

    @Override
    public int defer(Job job, UUID token, OffsetDateTime now, OffsetDateTime retryAt) {
        return tenant.defer(job, token, now, retryAt);
    }

    @Override
    public int revoke(Job job, OffsetDateTime now) {
        return tenant.revoke(job, now);
    }

    @Override
    public boolean lockCompletion(Job job, UUID token, OffsetDateTime now, int maxAttempts) {
        return !admin.lockSession(job).isEmpty()
                && !admin.lockRun(job).isEmpty()
                && !admin.lockAttempt(job).isEmpty()
                && !admin.lockJob(job, token, now, maxAttempts).isEmpty();
    }

    @Override
    public int finishAttempt(Job job, String status, String code, OffsetDateTime now) {
        return admin.finishAttempt(job, status, code, now);
    }

    @Override
    public boolean manifestReady(Job job) {
        return admin.manifestReady(job) == 1;
    }

    @Override
    public int finishJob(Job job, String status, OffsetDateTime now) {
        return admin.finishJob(job, status, now);
    }
}
