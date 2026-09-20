/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

/** Immutable identity and version of the execution environment bound to an agent call. */
public record ExecutionEnvironmentSnapshot(
        String environmentId, String providerId, String workspaceVersion, String version) {
    public ExecutionEnvironmentSnapshot {
        if (environmentId == null || environmentId.isBlank()) {
            throw new IllegalArgumentException("environmentId is required");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("environment version is required");
        }
    }
}
