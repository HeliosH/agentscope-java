/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.RunArtifactData;
import io.agentscope.saas.dal.mybatis.tenant.RunArtifactMapper;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant-scoped orchestration artifacts. */
@Repository
public class MyBatisRunArtifactRepository implements RunArtifactRepository {

    private final RunArtifactMapper mapper;

    public MyBatisRunArtifactRepository(RunArtifactMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean existsById(UUID id, UUID orgId) {
        return mapper.countById(id, orgId) > 0;
    }

    @Override
    public int insert(NewRunArtifact artifact) {
        return mapper.insert(artifact);
    }

    @Override
    public List<RunArtifact> findByRunId(UUID runId, UUID orgId) {
        return mapper.findByRunId(runId, orgId).stream()
                .map(MyBatisRunArtifactRepository::toDomain)
                .toList();
    }

    private static RunArtifact toDomain(RunArtifactData row) {
        return new RunArtifact(
                row.id(),
                row.orgId(),
                row.runId(),
                row.taskId(),
                row.attemptId(),
                row.fileId(),
                row.fileVersionId(),
                row.artifactType(),
                row.evidenceJson(),
                row.createdAt());
    }
}
