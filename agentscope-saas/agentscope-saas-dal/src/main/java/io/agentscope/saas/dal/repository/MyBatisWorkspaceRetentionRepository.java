/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.WorkspaceRetentionGcMapper;
import io.agentscope.saas.dal.mybatis.tenant.WorkspaceRetentionMapper;
import io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceRetentionRepository implements WorkspaceRetentionRepository {
    public Optional<Binding> binding(
            io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope scope,
            String sessionId,
            String providerId,
            String resourceId) {
        return tenant.binding(scope, sessionId, providerId, resourceId).stream().findFirst();
    }

    private final WorkspaceRetentionMapper tenant;
    private final WorkspaceRetentionGcMapper admin;

    public MyBatisWorkspaceRetentionRepository(
            WorkspaceRetentionMapper tenant, WorkspaceRetentionGcMapper admin) {
        this.tenant = tenant;
        this.admin = admin;
    }

    public long countHeld(UUID orgId) {
        return tenant.countHeld(orgId);
    }

    public void stage(Retention value) {
        if (tenant.lockTrackingResource(value).contains("terminating")
                || tenant.lockLeaseResource(value).contains("RELEASING"))
            throw new IllegalStateException("WORKSPACE_RETENTION_RESOURCE_RELEASING");
        if (tenant.holdTracking(value) != 1
                || tenant.holdLease(value) != 1
                || tenant.stage(value) != 1)
            throw new IllegalStateException("WORKSPACE_RETENTION_RESOURCE_FENCED");
        tenant.holdRelatedTracking(value);
        tenant.holdRelatedLeases(value);
    }

    public int ready(Retention value, OffsetDateTime now) {
        return tenant.ready(value, now);
    }

    public Optional<Retention> owned(UUID orgId, UUID userId, UUID sourceId) {
        return tenant.owned(orgId, userId, sourceId).stream().findFirst();
    }

    public List<Retention> due(OffsetDateTime now, int limit) {
        return admin.due(now, limit);
    }

    public int claim(Retention value, UUID token, OffsetDateTime now, OffsetDateTime until) {
        return admin.claim(value, token, now, until);
    }

    public void cleaned(Retention value, UUID token) {
        if (admin.cleaned(value, token) != 1)
            throw new IllegalStateException("WORKSPACE_RETENTION_CLEANUP_FENCED");
        // Parent rows may have cascaded away after cancellation or agent deletion.
        admin.releaseTracking(value);
        admin.releaseLease(value);
    }
}
