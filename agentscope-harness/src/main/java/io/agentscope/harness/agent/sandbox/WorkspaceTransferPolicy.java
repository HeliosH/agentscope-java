/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.sandbox;

/** Shared bounds for workspace hydration, projection, and checkpoint restoration. */
public record WorkspaceTransferPolicy(int maxFiles, long maxFileBytes, long maxTotalBytes) {
    public static final WorkspaceTransferPolicy DEFAULT =
            new WorkspaceTransferPolicy(5_000, 32L * 1024 * 1024, 256L * 1024 * 1024);

    public WorkspaceTransferPolicy {
        if (maxFiles < 1 || maxFileBytes < 1 || maxTotalBytes < maxFileBytes) {
            throw new IllegalArgumentException(
                    "Workspace limits must be positive and total bytes must cover one file");
        }
    }
}
