/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import java.util.UUID;

/** The compute result is durable; the caller must relinquish execution without retrying it. */
public final class WorkspaceArchiveDeferredException extends RuntimeException {
    private final UUID jobId;

    public WorkspaceArchiveDeferredException(UUID jobId) {
        super("Workspace output is being archived");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
