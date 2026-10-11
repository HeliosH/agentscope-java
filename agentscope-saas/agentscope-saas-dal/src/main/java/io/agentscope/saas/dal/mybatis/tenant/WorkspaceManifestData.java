/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceManifestData(
        UUID id,
        UUID orgId,
        UUID userId,
        UUID agentId,
        UUID sessionId,
        long sessionGeneration,
        UUID runId,
        UUID taskId,
        UUID agentRunId,
        UUID attemptId,
        String leaseOwner,
        long manifestSeq,
        String planHash,
        int fileCount,
        long totalBytes,
        int scannedFiles,
        String status,
        boolean scanComplete,
        Integer projectedFiles,
        Integer unchangedFiles,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
