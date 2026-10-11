/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable admission contract, distinct from a worker's physical transfer resources. */
public record RunExecutionPolicy(
        UUID orgId,
        UUID runId,
        int version,
        String deploymentScope,
        String capabilityHash,
        int maxFiles,
        long maxFileBytes,
        long maxWorkspaceBytes,
        boolean recoveryEnabled,
        String recoveryMode,
        int recoveryMaxAttempts,
        long recoveryInitialDelayMillis,
        long recoveryMaxDelayMillis,
        OffsetDateTime acceptedAt) {
    public RunExecutionPolicy {
        if (orgId == null
                || runId == null
                || version != 1
                || acceptedAt == null
                || deploymentScope == null
                || !deploymentScope.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || capabilityHash == null
                || !capabilityHash.matches("[a-f0-9]{64}")
                || maxFiles < 1
                || maxFiles == Integer.MAX_VALUE
                || maxFileBytes < 1
                || maxWorkspaceBytes < maxFileBytes
                || recoveryMaxAttempts < 1
                || recoveryInitialDelayMillis < 0
                || recoveryMaxDelayMillis < recoveryInitialDelayMillis
                || !("REQUEST_ONLY".equals(recoveryMode) || "DURABLE".equals(recoveryMode))) {
            throw new IllegalArgumentException("Invalid accepted Run execution policy");
        }
    }
}
