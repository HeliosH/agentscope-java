/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-call durable workspace publication receipt.
 *
 * <p>Interactive calls keep the Harness best-effort cleanup semantics. Durable workers put this
 * context into {@code RuntimeContext}; projection and catalog failures are then collected and
 * checked before the attempt can be marked successful.
 */
public final class WorkspaceCheckpointContext {

    private final boolean projectionAvailable;
    private final List<FileRecord> files = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private Integer projectedFiles;
    private boolean statePersisted;
    private boolean sandboxStopped;

    public WorkspaceCheckpointContext(boolean projectionAvailable) {
        this.projectionAvailable = projectionAvailable;
    }

    public synchronized void recordFile(FileRecord file) {
        if (file != null) {
            files.add(file);
        }
    }

    public synchronized void projectionSucceeded(int count) {
        projectedFiles = Math.max(0, count);
    }

    public synchronized void statePersisted() {
        statePersisted = true;
    }

    public synchronized void sandboxStopped() {
        sandboxStopped = true;
    }

    public synchronized void failed(String stage, Throwable error) {
        String message =
                error == null || error.getMessage() == null || error.getMessage().isBlank()
                        ? "unknown error"
                        : error.getMessage().trim().replaceAll("\\s+", " ");
        failures.add(stage + ": " + message);
    }

    public synchronized List<FileRecord> files() {
        return List.copyOf(files);
    }

    public synchronized boolean stateWasPersisted() {
        return statePersisted;
    }

    public synchronized boolean sandboxWasStopped() {
        return sandboxStopped;
    }

    public synchronized void verifyReady() {
        if (!projectionAvailable) {
            failures.add("workspace_projection: durable BaseStore is unavailable");
        } else if (projectedFiles == null) {
            failures.add("workspace_projection: completion was not observed");
        } else if (projectedFiles != files.size()) {
            failures.add(
                    "workspace_catalog: projected "
                            + projectedFiles
                            + " files but cataloged "
                            + files.size());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Durable workspace checkpoint failed: " + String.join("; ", failures));
        }
    }
}
