/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxIsolationOverride;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.config.TenantRlsWebFilter;
import io.agentscope.saas.app.model.ModelCatalog;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.app.workspace.WorkspaceProjectionCatalogSink;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.DurableTaskExecutor;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.orchestration.TaskContextAssembler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Executes a durable task through the existing HarnessAgent with a reconstructed tenant context. */
@Component
public class HarnessDurableTaskExecutor implements DurableTaskExecutor {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final SaasProperties properties;
    private final ChatPersistenceService chatPersistence;
    private final RunOrchestrationService orchestration;
    private final WorkspaceArtifactService workspaceArtifactService;
    private final WorkspaceCheckpointRestoreService workspaceRestoreService;
    private final boolean workspaceProjectionAvailable;
    private final TaskContextAssembler taskContextAssembler = new TaskContextAssembler();

    public HarnessDurableTaskExecutor(
            HarnessAgent agent,
            ObjectMapper objectMapper,
            SaasProperties properties,
            ChatPersistenceService chatPersistence,
            RunOrchestrationService orchestration,
            WorkspaceArtifactService workspaceArtifactService,
            WorkspaceCheckpointRestoreService workspaceRestoreService,
            Optional<BaseStore> workspaceStore) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatPersistence = chatPersistence;
        this.orchestration = orchestration;
        this.workspaceArtifactService = workspaceArtifactService;
        this.workspaceRestoreService = workspaceRestoreService;
        this.workspaceProjectionAvailable = workspaceStore.isPresent();
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) throws Exception {
        String previousOrgId = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(request.orgId().toString());
        try {
            return executeWithTenant(request);
        } finally {
            TenantContextHolder.setOrgId(previousOrgId);
        }
    }

    private ExecutionResult executeWithTenant(ExecutionRequest request) throws Exception {
        long startedNanos = System.nanoTime();
        TenantContext tenant =
                new TenantContext(
                        request.orgId().toString(),
                        request.userId().toString(),
                        request.role(),
                        request.tier(),
                        request.maxSandboxes(),
                        request.tokenQuota());
        String executionSessionId =
                request.subSessionId() != null && !request.subSessionId().isBlank()
                        ? request.subSessionId()
                        : request.sessionId().toString();
        RuntimeContext.Builder contextBuilder =
                RuntimeContext.builder()
                        .userId(request.userId().toString())
                        .sessionId(executionSessionId)
                        .put(
                                WorkspaceProjectionCatalogSink.ATTR_AGENT_ID,
                                request.agentId().toString())
                        .put(RunOrchestrationService.ATTR_RUN_ID, request.runId().toString())
                        .put(
                                io.agentscope.saas.sandbox.SandboxRuntimeAttributes.ATTR_TASK_ID,
                                request.taskId().toString())
                        .put(
                                io.agentscope.saas.sandbox.SandboxRuntimeAttributes.ATTR_ATTEMPT_ID,
                                request.attemptId().toString())
                        .put(
                                io.agentscope.saas.sandbox.SandboxRuntimeAttributes
                                        .ATTR_LEASE_OWNER,
                                request.leaseOwner())
                        .put(
                                RunOrchestrationService.ATTR_AGENT_RUN_ID,
                                request.agentRunId() != null
                                        ? request.agentRunId().toString()
                                        : null)
                        .put(TenantContext.class, tenant)
                        .put(TenantContext.ATTR_KEY, tenant);
        WorkspaceCheckpointContext workspaceCheckpoint =
                properties.getSandbox().isEnabled()
                        ? new WorkspaceCheckpointContext(workspaceProjectionAvailable)
                        : null;
        if (workspaceCheckpoint != null) {
            contextBuilder.put(WorkspaceCheckpointContext.class, workspaceCheckpoint);
            workspaceRestoreService
                    .prepare(
                            tenant,
                            request.orgId(),
                            request.attemptId(),
                            request.workspaceIsolationMode())
                    .ifPresent(
                            restorePlan ->
                                    contextBuilder.put(WorkspaceRestorePlan.class, restorePlan));
        }
        applyWorkspaceIsolation(contextBuilder, request);
        sharedSandboxIsolationKey(request)
                .ifPresent(
                        key ->
                                contextBuilder.put(
                                        SandboxIsolationOverride.class,
                                        new SandboxIsolationOverride(key)));
        runtimeAttribute(request, "modelId")
                .ifPresent(key -> contextBuilder.put(ContextWindowAwareModel.MODEL_ID_KEY, key));
        RuntimeContext context = contextBuilder.build();
        Map<String, Object> inputMetadata = new LinkedHashMap<>();
        inputMetadata.put(ModelCatalog.ORG_ID_KEY, request.orgId().toString());
        runtimeAttribute(request, "modelId")
                .ifPresent(key -> inputMetadata.put(ContextWindowAwareModel.MODEL_ID_KEY, key));
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(request.userId().toString())
                        .metadata(Map.copyOf(inputMetadata))
                        .textContent(taskContextAssembler.assemble(request))
                        .build();
        long timeout =
                Math.max(1L, properties.getOrchestration().getWorkerExecutionTimeoutSeconds());
        Msg result;
        try {
            result = executeAgent(request, input, context, timeout);
        } catch (RuntimeException | Error executionError) {
            publishCheckpoint(request, context, workspaceCheckpoint, executionError);
            throw executionError;
        }
        WorkspaceArtifactService.Publication publication =
                publishCheckpoint(request, context, workspaceCheckpoint, null);
        if (result == null) {
            throw new IllegalStateException("HarnessAgent completed without a result message");
        }
        if (isContinuation(request) && !orchestration.hasUnsettledChildren(request.runId())) {
            chatPersistence.saveAssistantMessageForRun(
                    tenant,
                    request.sessionId(),
                    request.agentId(),
                    request.runId(),
                    result.getContent());
        }
        String fullResult = result.getTextContent() != null ? result.getTextContent() : "";
        String summary =
                bounded(
                        fullResult,
                        properties.getOrchestration().getWorkerResultSummaryMaxCharacters());
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.add(
                Map.of(
                        "source",
                        "agent_result",
                        "sha256",
                        sha256(fullResult),
                        "length",
                        fullResult.length(),
                        "summaryTruncated",
                        summary.length() < fullResult.length()));
        List<String> artifactRefs = new ArrayList<>();
        if (publication != null && publication.artifactCount() > 0) {
            artifactRefs.add(publication.uri());
            evidence.add(
                    Map.of(
                            "source",
                            "workspace_checkpoint",
                            "uri",
                            publication.uri(),
                            "version",
                            publication.version(),
                            "artifactCount",
                            publication.artifactCount()));
        }
        return new ExecutionResult(
                objectMapper.writeValueAsString(
                        Map.of(
                                "status",
                                "succeeded",
                                "summary",
                                summary,
                                "evidence",
                                evidence,
                                "artifactRefs",
                                artifactRefs,
                                "followUpTasks",
                                List.of(),
                                "usage",
                                Map.of(
                                        "tokens",
                                        0,
                                        "durationMillis",
                                        Duration.ofNanos(System.nanoTime() - startedNanos)
                                                .toMillis(),
                                        "sandboxSeconds",
                                        0))));
    }

    private WorkspaceArtifactService.Publication publishCheckpoint(
            ExecutionRequest request,
            RuntimeContext context,
            WorkspaceCheckpointContext checkpoint,
            Throwable executionError) {
        if (checkpoint == null) {
            return null;
        }
        try {
            return workspaceArtifactService.publish(
                    request.orgId(),
                    request.runId(),
                    request.taskId(),
                    request.attemptId(),
                    context.get(io.agentscope.saas.sandbox.SandboxLeaseContext.class),
                    checkpoint);
        } catch (RuntimeException checkpointError) {
            if (executionError != null) {
                checkpointError.addSuppressed(executionError);
            }
            throw checkpointError;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String bounded(String value, int maxCharacters) {
        int limit = Math.max(1, maxCharacters);
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private Msg executeAgent(
            ExecutionRequest request, Msg input, RuntimeContext context, long timeoutSeconds) {
        Mono<Msg> execution;
        if (request.agentType() == null || "assistant".equals(request.agentType())) {
            execution = agent.call(input, context);
        } else {
            Agent child =
                    agent.createSubagentIfPresent(request.agentType(), context)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown durable subagent: "
                                                            + request.agentType()));
            if (child instanceof HarnessAgent harness) {
                execution = harness.call(input, context);
            } else if (child instanceof ReActAgent react) {
                execution = react.call(List.of(input), context);
            } else {
                execution = child.call(List.of(input));
            }
        }
        return execution
                .contextWrite(
                        reactorContext ->
                                reactorContext.put(
                                        TenantRlsWebFilter.ORG_ID_KEY, request.orgId().toString()))
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    private static void applyWorkspaceIsolation(
            RuntimeContext.Builder context, ExecutionRequest request) {
        WorkspaceIsolationMode mode = request.workspaceIsolationMode();
        switch (mode) {
            case NONE -> {
                // Interactive coordinator tasks retain the deployment's configured scope.
            }
            case RUN_ISOLATED ->
                    context.put(
                            SandboxIsolationOverride.class,
                            new SandboxIsolationOverride("run/" + request.runId()));
            case ATTEMPT_ISOLATED, DEDICATED_SANDBOX ->
                    context.put(
                            SandboxIsolationOverride.class,
                            new SandboxIsolationOverride("attempt/" + request.attemptId()));
            case USER_SHARED_READ_ONLY ->
                    throw new IllegalStateException(
                            "USER_SHARED_READ_ONLY requires a read-only workspace adapter");
        }
    }

    private Optional<String> sharedSandboxIsolationKey(ExecutionRequest request) {
        return runtimeAttribute(request, "sandboxIsolationKey");
    }

    private Optional<String> runtimeAttribute(ExecutionRequest request, String attribute) {
        try {
            JsonNode root = objectMapper.readTree(request.inputJson());
            if (root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            String key = root.path("_runtime").path(attribute).asText("").trim();
            return key.isEmpty() ? Optional.empty() : Optional.of(key);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read durable task runtime metadata", e);
        }
    }

    private boolean isContinuation(ExecutionRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.inputJson());
            if (root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            return root.path("continuation").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
