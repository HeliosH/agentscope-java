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
import io.agentscope.saas.app.orchestration.SessionRunFenceService;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
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

    public WorkspaceProjectionCatalogSink(FileCatalogService fileCatalogService) {
        this.fileCatalogService = fileCatalogService;
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
                                    Map.of("source", "sandbox.release.projection"))
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
                                            Map.of("source", "sandbox.release.projection"))
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
                                            Map.of("source", "sandbox.release.projection"));
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
