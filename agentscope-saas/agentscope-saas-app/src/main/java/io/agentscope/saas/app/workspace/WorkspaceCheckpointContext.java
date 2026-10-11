/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final Map<String, UUID> baselineVersions;
    private Manifest manifest;

    public synchronized void planned(Manifest value) {
        java.util.Objects.requireNonNull(value);
        if (manifest != null) {
            var prior = manifest.scope();
            var next = value.scope();
            if (!prior.orgId().equals(next.orgId())
                    || !prior.userId().equals(next.userId())
                    || !prior.agentId().equals(next.agentId())
                    || !prior.sessionId().equals(next.sessionId())
                    || prior.sessionGeneration() != next.sessionGeneration()
                    || !prior.runId().equals(next.runId())
                    || !prior.execution().taskId().equals(next.execution().taskId()))
                throw new IllegalStateException("WORKSPACE_MANIFEST_SCOPE_CHANGED");
        }
        // A request-level retry can reuse this context, but not the previous scan's receipts.
        files.clear();
        failures.clear();
        projectedFiles = null;
        statePersisted = false;
        sandboxStopped = false;
        this.manifest = value;
    }

    public synchronized Manifest manifest() {
        return manifest;
    }

    public WorkspaceCheckpointContext(boolean projectionAvailable) {
        this(projectionAvailable, null);
    }

    public WorkspaceCheckpointContext(
            boolean projectionAvailable, Map<String, UUID> baselineVersions) {
        this.projectionAvailable = projectionAvailable;
        this.baselineVersions = baselineVersions == null ? null : Map.copyOf(baselineVersions);
    }

    public synchronized Map<String, Object> publicationMetadata(String path) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "sandbox.release.projection");
        if (manifest != null) metadata.put("workspaceManifestId", manifest.id().toString());
        if (baselineVersions == null) return Map.copyOf(metadata);
        UUID version =
                baselineVersions.get(path.trim().replaceFirst("^/+", "").replaceFirst("/+$", ""));
        metadata.put("runBaselineCaptured", true);
        metadata.put("runBaselineVersionId", version == null ? "" : version.toString());
        return Map.copyOf(metadata);
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
