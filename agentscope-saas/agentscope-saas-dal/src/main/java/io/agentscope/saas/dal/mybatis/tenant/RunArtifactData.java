/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** MyBatis data object for an immutable orchestration artifact. */
public record RunArtifactData(
        UUID id,
        UUID orgId,
        UUID runId,
        UUID taskId,
        UUID attemptId,
        UUID fileId,
        UUID fileVersionId,
        String logicalPath,
        String artifactType,
        String evidenceJson,
        OffsetDateTime createdAt) {}
