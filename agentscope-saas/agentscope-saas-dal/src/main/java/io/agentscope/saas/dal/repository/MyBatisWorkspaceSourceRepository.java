/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.WorkspaceSourceGcMapper;
import io.agentscope.saas.dal.mybatis.tenant.WorkspaceSourceMapper;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceSourceRepository implements WorkspaceSourceRepository {
    private final WorkspaceSourceMapper tenant;
    private final WorkspaceSourceGcMapper admin;

    public MyBatisWorkspaceSourceRepository(
            WorkspaceSourceMapper tenant, WorkspaceSourceGcMapper admin) {
        this.tenant = tenant;
        this.admin = admin;
    }

    public long reserved(UUID org) {
        return tenant.reserved(org);
    }

    public void stage(Source source, List<Baseline> files) {
        if (tenant.stage(source) != 1)
            throw new IllegalStateException("WORKSPACE_SOURCE_NOT_STAGED");
        for (int start = 0; start < files.size(); start += 100) {
            var chunk = files.subList(start, Math.min(files.size(), start + 100));
            if (tenant.stageFiles(source, chunk) != chunk.size())
                throw new IllegalStateException("WORKSPACE_SOURCE_BASELINE_MISSING");
        }
    }

    public List<Baseline> baselines(Source source) {
        return tenant.baselines(source);
    }

    public int retain(Source source, OffsetDateTime now) {
        return tenant.retain(source, now);
    }

    public Optional<Source> lockOwned(UUID org, UUID user, UUID manifest) {
        return tenant.lockOwned(org, user, manifest).stream().findFirst();
    }

    public List<Source> candidates(OffsetDateTime now, int limit) {
        return admin.candidates(now, limit);
    }

    public int claim(Source s, UUID token, OffsetDateTime now, OffsetDateTime until) {
        return admin.claim(s, token, now, until);
    }

    public int collected(Source s, UUID token, OffsetDateTime retryAt) {
        return admin.collected(s, token, retryAt);
    }

    public int failed(Source s, UUID token, OffsetDateTime retryAt) {
        return admin.failed(s, token, retryAt);
    }
}
