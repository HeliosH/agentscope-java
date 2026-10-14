/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolRetrySafety;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxUsageGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceInputPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** Metadata discovery and explicit version retrieval, never an employee workspace scan. */
public final class WorkspaceFilesTool {
    private final WorkspaceInputService inputs;
    private final WorkspaceTransferResources transfers;
    private final ObjectMapper json;

    public WorkspaceFilesTool(
            WorkspaceInputService inputs, WorkspaceTransferResources transfers, ObjectMapper json) {
        this.inputs = inputs;
        this.transfers = transfers;
        this.json = json;
    }

    @Tool(
            name = "file_search",
            readOnly = true,
            retrySafety = ToolRetrySafety.READ_ONLY,
            description =
                    "Search your authorized uploaded/generated file names. Returns immutable"
                        + " fileId/versionId pairs with a pagination cursor, without reading file"
                        + " contents. Use file_fetch with an explicit pair to load a historical"
                        + " file for this task.")
    public String search(
            RuntimeContext context,
            @ToolParam(name = "query", description = "Literal file name or path text") String query,
            @ToolParam(
                            name = "after_path",
                            description = "Pagination cursor from the previous page",
                            required = false)
                    String afterPath,
            @ToolParam(name = "limit", description = "Page size, 1 to 100", required = false)
                    Integer limit)
            throws Exception {
        var scope = inputs.executionScope(context);
        int size = limit == null ? 25 : limit;
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(scope.orgId().toString());
        try {
            var files = inputs.search(scope, query, afterPath == null ? "" : afterPath, size);
            var page = files.subList(0, Math.min(size, files.size()));
            return json.writeValueAsString(
                    Map.of(
                            "files",
                            page.stream()
                                    .map(
                                            f ->
                                                    Map.of(
                                                            "fileId",
                                                            f.fileId(),
                                                            "versionId",
                                                            f.versionId(),
                                                            "path",
                                                            f.logicalPath(),
                                                            "sizeBytes",
                                                            f.sizeBytes()))
                                    .toList(),
                            "nextCursor",
                            files.size() > size ? page.get(page.size() - 1).logicalPath() : ""));
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    @Tool(
            name = "file_fetch",
            readOnly = false,
            concurrencySafe = false,
            retrySafety = ToolRetrySafety.IDEMPOTENT,
            description =
                    "Load one explicitly selected immutable file version into this task's"
                        + " inputs/retrieved directory. Requires the fileId and versionId returned"
                        + " by file_search. Checks current authorization and the total task"
                        + " file/byte budget. Existing working copies are preserved.")
    public String fetch(
            RuntimeContext context,
            @ToolParam(name = "file_id", description = "Authorized file UUID") String fileId,
            @ToolParam(name = "version_id", description = "Explicit immutable version UUID")
                    String versionId)
            throws Exception {
        var sandbox = context.get(Sandbox.class);
        if (sandbox == null || !sandbox.isRunning())
            throw new IllegalStateException("File retrieval requires an active task sandbox");
        requireCurrent(context, sandbox);
        inputs.requirePlanCurrent(context);
        var scope = inputs.executionScope(context);
        var previous = context.get(WorkspaceInputPlan.class);
        var retrieved =
                inputs.retrieve(
                        TenantContext.from(context),
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().attemptId(),
                        scope.execution().leaseOwner(),
                        UUID.fromString(fileId),
                        UUID.fromString(versionId));
        var file =
                retrieved.plan().workspace().files().stream()
                        .filter(f -> f.path().equals(retrieved.path()))
                        .findFirst()
                        .orElseThrow();
        boolean present =
                previous != null
                        && previous.workspace().files().stream()
                                .anyMatch(
                                        f ->
                                                f.path().equals(file.path())
                                                        && f.sha256().equals(file.sha256()));
        if (!present) {
            try (var permit = transfers.acquire();
                    var spool =
                            transfers.spool(
                                    "task-input-fetch-", Math.addExact(file.size(), 4096L))) {
                try (var tar = new TarArchiveOutputStream(spool.output())) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    ArchiveOutputStream<TarArchiveEntry> archive = tar;
                    var entry = new TarArchiveEntry(file.path());
                    entry.setSize(file.size());
                    entry.setMode(0644);
                    archive.putArchiveEntry(entry);
                    file.transferTo(tar, transfers.bufferBytes());
                    tar.closeArchiveEntry();
                    tar.finish();
                }
                requireCurrent(context, sandbox);
                try (var stream = Files.newInputStream(spool.path())) {
                    sandbox.hydrateWorkspace(stream);
                }
                requireCurrent(context, sandbox);
            }
        }
        context.put(WorkspaceInputPlan.class, retrieved.plan());
        context.put(
                WorkspaceInputPlan.Materialized.class,
                new WorkspaceInputPlan.Materialized(
                        sandbox.getState().getSessionId(),
                        retrieved.plan().workspace().workspaceVersion()));
        var environment = context.get(io.agentscope.core.tool.ExecutionEnvironmentSnapshot.class);
        if (environment != null) {
            String workspace =
                    io.agentscope.harness.agent.sandbox.WorkspaceVersions.current(context);
            context.put(
                    io.agentscope.core.tool.ExecutionEnvironmentSnapshot.class,
                    new io.agentscope.core.tool.ExecutionEnvironmentSnapshot(
                            environment.environmentId(),
                            environment.providerId(),
                            workspace,
                            io.agentscope.core.tool.StepSnapshot.fingerprint(
                                    Map.of(
                                            "environmentId",
                                            environment.environmentId(),
                                            "providerId",
                                            environment.providerId(),
                                            "workspaceVersion",
                                            workspace))));
        }
        inputs.requirePlanCurrent(context);
        return json.writeValueAsString(
                Map.of(
                        "path",
                        file.path(),
                        "fileId",
                        fileId,
                        "versionId",
                        versionId,
                        "sizeBytes",
                        file.size(),
                        "sha256",
                        file.sha256(),
                        "workspaceVersion",
                        retrieved.plan().workspace().workspaceVersion()));
    }

    private static void requireCurrent(RuntimeContext context, Sandbox sandbox) {
        var guard = context.get(SandboxUsageGuard.class);
        if (guard != null)
            guard.requireCurrent(context, context.get(SandboxUsageGuard.Binding.class), sandbox);
    }
}
