/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.ContextCheckpointData;
import io.agentscope.saas.dal.mybatis.admin.ContextCheckpointMapper;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.orchestration.ContextCheckpoint;
import io.agentscope.saas.domain.orchestration.ContextCheckpointRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for durable context checkpoints. */
@Repository
public class MyBatisContextCheckpointRepository implements ContextCheckpointRepository {
    private final ContextCheckpointMapper mapper;

    public MyBatisContextCheckpointRepository(ContextCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean lockArchiveScope(UUID orgId, UUID runId, Scope scope) {
        return mapper.lockArchiveScope(orgId, runId, scope).size() == 1;
    }

    @Override
    public int attachBody(Scope scope, UUID bodyId, OffsetDateTime eligibleAt) {
        return mapper.attachBody(scope, bodyId, eligibleAt);
    }

    @Override
    public int insertBodyReference(UUID checkpointId, UUID orgId, UUID bodyId) {
        return mapper.insertBodyReference(checkpointId, orgId, bodyId);
    }

    @Override
    public boolean lockActiveScope(
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner,
            OffsetDateTime now) {
        return mapper.lockActiveScope(orgId, runId, taskId, agentRunId, attemptId, leaseOwner, now)
                        .size()
                == 1;
    }

    @Override
    public long latestRevision(UUID orgId, UUID runId, UUID agentRunId) {
        return mapper.latestRevision(orgId, runId, agentRunId);
    }

    @Override
    public int insert(NewCheckpoint checkpoint) {
        return mapper.insert(checkpoint);
    }

    @Override
    public Optional<ContextCheckpoint> findLatest(UUID orgId, UUID runId, UUID agentRunId) {
        return mapper.findLatest(orgId, runId, agentRunId).stream()
                .findFirst()
                .map(MyBatisContextCheckpointRepository::toDomain);
    }

    private static ContextCheckpoint toDomain(ContextCheckpointData row) {
        return new ContextCheckpoint(
                row.id(),
                row.orgId(),
                row.runId(),
                row.taskId(),
                row.agentRunId(),
                row.attemptId(),
                row.historyRevision(),
                row.stepId(),
                row.historyHash(),
                row.summary(),
                row.retainedTailJson(),
                row.retainedFactsVersion(),
                row.pendingOperationsJson(),
                row.workspaceVersion(),
                row.createdAt());
    }
}
