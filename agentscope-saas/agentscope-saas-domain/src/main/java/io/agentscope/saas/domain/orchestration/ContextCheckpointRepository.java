/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.orchestration;

import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for monotonic, lease-fenced conversation checkpoints. */
public interface ContextCheckpointRepository {

    boolean lockArchiveScope(UUID orgId, UUID runId, Scope scope);

    int attachBody(Scope scope, UUID bodyId, OffsetDateTime eligibleAt);

    int insertBodyReference(UUID checkpointId, UUID orgId, UUID bodyId);

    boolean lockActiveScope(
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String leaseOwner,
            OffsetDateTime now);

    long latestRevision(UUID orgId, UUID runId, UUID agentRunId);

    int insert(NewCheckpoint checkpoint);

    Optional<ContextCheckpoint> findLatest(UUID orgId, UUID runId, UUID agentRunId);

    record NewCheckpoint(
            UUID id,
            UUID orgId,
            UUID runId,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            long historyRevision,
            String stepId,
            String historyHash,
            String summary,
            String retainedTailJson,
            String retainedFactsVersion,
            String pendingOperationsJson,
            String workspaceVersion,
            OffsetDateTime createdAt) {}
}
