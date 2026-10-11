/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.util.UUID;

public record WorkspaceRestoreScopeData(
        UUID orgId,
        UUID userId,
        UUID agentId,
        UUID sessionId,
        long sessionGeneration,
        UUID runId,
        UUID taskId,
        UUID agentRunId,
        UUID attemptId,
        String leaseOwner) {}
