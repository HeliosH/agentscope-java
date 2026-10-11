/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.harness.agent.filesystem.sandbox.WorkspaceProjectionSink;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;
import io.agentscope.saas.app.orchestration.SessionRunFenceService;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Persists sandbox-produced release projection files into the enterprise file catalog. */
@Component
public class WorkspaceProjectionCatalogSink implements WorkspaceProjectionSink {

    public static final String ATTR_AGENT_ID = SandboxRuntimeAttributes.ATTR_AGENT_ID;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProjectionCatalogSink.class);

    private final FileCatalogService fileCatalogService;
    private final WorkspaceManifestService manifests;
    private final WorkspaceSourceService sources;
    private org.springframework.beans.factory.ObjectProvider<WorkspaceArchiveService> archives;

    @org.springframework.beans.factory.annotation.Autowired
    void configureComputedHandover(
            org.springframework.beans.factory.ObjectProvider<WorkspaceArchiveService> value) {
        archives = value;
    }

    public WorkspaceProjectionCatalogSink(
            FileCatalogService fileCatalogService, WorkspaceManifestService manifests) {
        this(fileCatalogService, manifests, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceProjectionCatalogSink(
            FileCatalogService fileCatalogService,
            WorkspaceManifestService manifests,
            WorkspaceSourceService sources) {
        this.fileCatalogService = fileCatalogService;
        this.manifests = manifests;
        this.sources = sources;
    }

    @Override
    public void onProjectionCaptured(
            RuntimeContext context, WorkspaceProjectionPlan plan, java.nio.file.Path snapshot) {
        var checkpoint = context == null ? null : context.get(WorkspaceCheckpointContext.class);
        if (sources == null || checkpoint == null || checkpoint.manifest() == null) return;
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(checkpoint.manifest().scope().orgId().toString());
        Runnable sealed =
                () -> {
                    var computed =
                            context.get(
                                    io.agentscope.harness.agent.sandbox.InvocationResultObserver
                                            .class);
                    if (computed instanceof WorkspaceComputedResult result && archives != null)
                        result.sealed(checkpoint, archives.getObject());
                };
        try {
            sources.capture(checkpoint.manifest(), plan, snapshot, sealed);
        } catch (io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException revoked) {
            throw revoked;
        } catch (Exception error) {
            try {
                if (sources.captureRetention(
                        context, checkpoint.manifest(), plan, snapshot, sealed)) return;
            } catch (
                    io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                            revoked) {
                throw revoked;
            } catch (Exception retentionFailure) {
                log.warn(
                        "Workspace provider retention unavailable manifest={} ({})",
                        checkpoint.manifest().id(),
                        retentionFailure.getClass().getSimpleName());
            }
            log.warn(
                    "Workspace source escrow unavailable manifest={} ({})",
                    checkpoint.manifest().id(),
                    error.getClass().getSimpleName());
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    @Override
    public void onProjectionPlanned(RuntimeContext context, WorkspaceProjectionPlan plan) {
        if (context == null) return;
        var checkpoint = context.get(WorkspaceCheckpointContext.class);
        if (checkpoint == null) return;
        var binding = context.get(SessionRunFenceService.Binding.class);
        if (binding == null || binding.runId() == null)
            throw new SessionExecutionRevokedException();
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(binding.orgId().toString());
        try {
            checkpoint.planned(
                    manifests.freeze(
                            new Scope(
                                    binding.orgId(),
                                    binding.userId(),
                                    binding.agentId(),
                                    binding.fence().sessionId(),
                                    binding.fence().generation(),
                                    binding.runId(),
                                    execution(context, binding.runId())),
                            plan));
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    @Override
    public void onProjectionCompleted(RuntimeContext context, WorkspaceProjectionReport report) {
        if (context == null) return;
        var checkpoint = context.get(WorkspaceCheckpointContext.class);
        if (checkpoint == null) return;
        var manifest = checkpoint.manifest();
        if (manifest == null) throw new IllegalStateException("WORKSPACE_MANIFEST_MISSING");
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(manifest.scope().orgId().toString());
        try {
            manifests.scanCompleted(manifest, report);
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    @Override
    public boolean supportsStreamProjection(RuntimeContext context) {
        if (context == null) return false;
        var checkpoint = context.get(WorkspaceCheckpointContext.class);
        var binding = context.get(SessionRunFenceService.Binding.class);
        return checkpoint != null
                && checkpoint.manifest() != null
                && binding != null
                && binding.runId() != null;
    }

    @Override
    public void onProjectedFile(
            RuntimeContext context,
            String path,
            java.nio.file.Path content,
            long size,
            String sha256) {
        var checkpoint = context.get(WorkspaceCheckpointContext.class);
        var binding = context.get(SessionRunFenceService.Binding.class);
        var tenant = TenantContext.from(context);
        if (checkpoint == null || binding == null || tenant == null)
            throw new SessionExecutionRevokedException();
        var record =
                fileCatalogService
                        .recordWorkspacePathForAttempt(
                                tenant,
                                binding.agentId(),
                                binding.runId(),
                                binding.fence(),
                                execution(context, binding.runId()),
                                path,
                                content,
                                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                FileCatalogService.SOURCE_SANDBOX_PROJECTION,
                                checkpoint.publicationMetadata(path))
                        .orElseThrow();
        if (record.sizeBytes() != size || !record.sha256().equals(sha256))
            throw new IllegalStateException("FILE_PUBLICATION_INTEGRITY_FAILED");
        checkpoint.recordFile(record);
    }

    @Override
    public void onProjectedFile(RuntimeContext runtimeContext, String path, byte[] content) {
        WorkspaceCheckpointContext checkpoint =
                runtimeContext != null
                        ? runtimeContext.get(WorkspaceCheckpointContext.class)
                        : null;
        TenantContext tenant = TenantContext.from(runtimeContext);
        if (tenant == null) {
            if (checkpoint != null) {
                checkpoint.failed(
                        "workspace_catalog",
                        new IllegalStateException("tenant context is unavailable"));
            }
            return;
        }
        try {
            var binding = runtimeContext.get(SessionRunFenceService.Binding.class);
            if (binding == null && runtimeContext.get(RunOrchestrationService.ATTR_RUN_ID) != null)
                throw new SessionExecutionRevokedException();
            var publication =
                    binding != null && binding.runId() != null
                            ? fileCatalogService.recordWorkspaceFileForAttempt(
                                    tenant,
                                    binding.agentId(),
                                    binding.runId(),
                                    binding.fence(),
                                    execution(runtimeContext, binding.runId()),
                                    path,
                                    content,
                                    MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                    FileCatalogService.SOURCE_SANDBOX_PROJECTION,
                                    checkpoint == null
                                            ? Map.of("source", "sandbox.release.projection")
                                            : checkpoint.publicationMetadata(path))
                            : binding != null
                                    ? fileCatalogService.recordWorkspaceFileForExecution(
                                            tenant,
                                            binding.agentId(),
                                            binding.runId(),
                                            binding.fence(),
                                            path,
                                            content,
                                            MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                            FileCatalogService.SOURCE_SANDBOX_PROJECTION,
                                            checkpoint == null
                                                    ? Map.of("source", "sandbox.release.projection")
                                                    : checkpoint.publicationMetadata(path))
                                    : fileCatalogService.recordWorkspaceFile(
                                            tenant,
                                            parseUuid(
                                                    runtimeContext != null
                                                            ? runtimeContext.get(ATTR_AGENT_ID)
                                                            : null),
                                            parseUuid(
                                                    runtimeContext != null
                                                            ? runtimeContext.getSessionId()
                                                            : null),
                                            path,
                                            content,
                                            MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                            FileCatalogService.SOURCE_SANDBOX_PROJECTION,
                                            checkpoint == null
                                                    ? Map.of("source", "sandbox.release.projection")
                                                    : checkpoint.publicationMetadata(path));
            publication.ifPresentOrElse(
                    record -> {
                        if (checkpoint != null) {
                            checkpoint.recordFile(record);
                        }
                    },
                    () -> {
                        if (checkpoint != null) {
                            checkpoint.failed(
                                    "workspace_catalog",
                                    new IllegalStateException("file object store is unavailable"));
                        }
                    });
        } catch (Exception e) {
            if (checkpoint != null) {
                checkpoint.failed("workspace_catalog", e);
            }
            log.warn(
                    "Failed to catalog sandbox projected file {} for session {}: {}",
                    path,
                    runtimeContext != null ? runtimeContext.getSessionId() : null,
                    e.getMessage());
        }
    }

    @Override
    public void onDeletedFile(RuntimeContext runtimeContext, String path) {
        WorkspaceCheckpointContext checkpoint =
                runtimeContext != null
                        ? runtimeContext.get(WorkspaceCheckpointContext.class)
                        : null;
        TenantContext tenant = TenantContext.from(runtimeContext);
        if (tenant == null) {
            if (checkpoint != null) {
                checkpoint.failed(
                        "workspace_catalog_delete",
                        new IllegalStateException("tenant context is unavailable"));
            }
            return;
        }
        try {
            var binding = runtimeContext.get(SessionRunFenceService.Binding.class);
            if (binding != null)
                fileCatalogService.markDeletedForExecution(
                        tenant, binding.agentId(), binding.runId(), binding.fence(), path);
            else {
                if (runtimeContext.get(RunOrchestrationService.ATTR_RUN_ID) != null)
                    throw new SessionExecutionRevokedException();
                fileCatalogService.markDeleted(tenant, path);
            }
        } catch (Exception e) {
            if (checkpoint != null) {
                checkpoint.failed("workspace_catalog_delete", e);
            }
            log.warn("Failed to catalog sandbox projected delete {}: {}", path, e.getMessage());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Execution execution(RuntimeContext context, UUID run) {
        StepSnapshot.Identity identity = context.get(StepSnapshot.Identity.class);
        ExecutionLeaseSnapshot lease = context.get(ExecutionLeaseSnapshot.class);
        if (identity == null || lease == null || !run.toString().equals(identity.runId()))
            throw new IllegalStateException("Publication execution scope unavailable");
        return new Execution(
                UUID.fromString(identity.taskId()),
                UUID.fromString(identity.agentRunId()),
                UUID.fromString(identity.attemptId()),
                lease.owner());
    }
}
