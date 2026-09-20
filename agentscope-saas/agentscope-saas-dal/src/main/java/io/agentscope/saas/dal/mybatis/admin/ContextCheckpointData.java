/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrative projection of a durable context checkpoint. */
public record ContextCheckpointData(
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
