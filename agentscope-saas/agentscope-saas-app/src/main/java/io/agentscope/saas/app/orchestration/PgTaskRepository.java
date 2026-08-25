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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationOverride;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.DurableTask;
import io.agentscope.saas.domain.orchestration.DurableTaskRepository;
import io.agentscope.saas.domain.orchestration.DurableTaskRepository.TaskScope;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed bridge for Harness background subagent tools. */
@Component
public class PgTaskRepository implements TaskRepository {

    private final DurableTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final RunOrchestrationService orchestration;
    private final SaasProperties properties;

    public PgTaskRepository(
            DurableTaskRepository taskRepository,
            ObjectMapper objectMapper,
            RunOrchestrationService orchestration,
            SaasProperties properties) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.orchestration = orchestration;
        this.properties = properties;
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        return taskRepository
                .findLatest(scope.taskScope(), required(taskId, "taskId"))
                .map(this::toBackgroundTask)
                .orElse(null);
    }

    @Override
    public BackgroundTask putTask(
            RuntimeContext rc,
            String taskId,
            String subAgentId,
            String sessionId,
            TaskRunSpec spec) {
        Scope scope = scope(rc, sessionId);
        if (!(spec instanceof TaskRunSpec.DurableLocalTaskRunSpec local)) {
            throw new IllegalArgumentException(
                    "Durable subagent mode requires a reconstructable local task specification");
        }
        String runValue = rc.get(RunOrchestrationService.ATTR_RUN_ID);
        String agentValue = rc.get(SandboxRuntimeAttributes.ATTR_AGENT_ID);
        if (runValue == null || agentValue == null) {
            throw new IllegalStateException(
                    "Durable subagent submission requires Run and Agent ids");
        }
        TenantContext tenant = scope.tenant();
        String inputJson;
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", local.input());
            input.put("externalTaskId", taskId);
            input.put("subSessionId", local.subSessionId());
            sharedSandboxIsolationKey(rc)
                    .ifPresent(key -> input.put("_runtime", Map.of("sandboxIsolationKey", key)));
            inputJson = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to serialize durable subagent input", e);
        }
        orchestration.createSubagentTask(
                tenant,
                UUID.fromString(agentValue),
                UUID.fromString(runValue),
                optionalUuid(rc.get(RunOrchestrationService.ATTR_AGENT_RUN_ID), "agentRunId"),
                taskId,
                subAgentId,
                local.subSessionId(),
                inputJson,
                subagentPolicy());
        return getTask(rc, sessionId, taskId);
    }

    private static Optional<String> sharedSandboxIsolationKey(RuntimeContext context) {
        SandboxIsolationOverride override = context.get(SandboxIsolationOverride.class);
        if (override != null) {
            return Optional.of(override.key());
        }
        SandboxContext sandbox = context.get(SandboxContext.class);
        if (sandbox != null
                && sandbox.getIsolationScope() == IsolationScope.SESSION
                && context.getSessionId() != null
                && !context.getSessionId().isBlank()) {
            return Optional.of(context.getSessionId());
        }
        return Optional.empty();
    }

    @Override
    public void removeTask(RuntimeContext rc, String sessionId, String taskId) {
        markDelivered(rc, sessionId, taskId);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(
                "Durable task history cannot be cleared without an explicit tenant scope");
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext rc, String sessionId, TaskStatus filter) {
        Scope scope = scope(rc, sessionId);
        return taskRepository.findAll(scope.taskScope()).stream()
                .filter(row -> filter == null || taskStatus(row.status()) == filter)
                .map(this::toBackgroundTask)
                .toList();
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        DurableTask row = findRow(scope, taskId);
        if (row == null || taskStatus(row.status()).isTerminal()) {
            return row != null;
        }
        return orchestration.cancelSubagentTask(
                scope.tenant(), row.agentId(), row.runId(), row.id());
    }

    @Override
    public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        Scope scope = scope(rc, sessionId);
        return taskRepository.findPendingDeliveries(scope.taskScope()).stream()
                .map(
                        row -> {
                            TaskStatus status = taskStatus(row.status());
                            return new TaskDelivery(
                                    row.externalTaskId(),
                                    row.agentType(),
                                    status,
                                    status == TaskStatus.COMPLETED ? result(row) : null,
                                    status == TaskStatus.FAILED ? row.errorMessage() : null,
                                    (row.completedAt() != null
                                                    ? row.completedAt()
                                                    : row.createdAt())
                                            .toInstant());
                        })
                .toList();
    }

    @Override
    public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        taskRepository.markDelivered(
                scope.taskScope(), required(taskId, "taskId"), OffsetDateTime.now());
    }

    @Override
    public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        DurableTask row = findRow(scope, taskId);
        return row != null && row.deliveredAt() != null;
    }

    private DurableTask findRow(Scope scope, String taskId) {
        return taskRepository
                .findLatest(scope.taskScope(), required(taskId, "taskId"))
                .orElse(null);
    }

    private BackgroundTask toBackgroundTask(DurableTask row) {
        CompletableFuture<String> future = new CompletableFuture<>();
        switch (taskStatus(row.status())) {
            case COMPLETED -> future.complete(result(row));
            case FAILED ->
                    future.completeExceptionally(
                            new IllegalStateException(
                                    row.errorMessage() != null
                                            ? row.errorMessage()
                                            : "Durable subagent task failed"));
            case CANCELLED -> future.cancel(false);
            default -> {
                // Incomplete future represents a task owned by a durable worker.
            }
        }
        return new BackgroundTask(row.externalTaskId(), row.agentType(), future);
    }

    private String result(DurableTask row) {
        try {
            JsonNode node = objectMapper.readTree(row.outputJson());
            if (node.isTextual()) {
                node = objectMapper.readTree(node.textValue());
            }
            String summary = node.path("summary").asText("");
            return summary.isBlank() ? row.outputJson() : summary;
        } catch (Exception ignored) {
            return row.outputJson();
        }
    }

    private static TaskStatus taskStatus(String status) {
        return switch (status) {
            case "SUCCEEDED" -> TaskStatus.COMPLETED;
            case "FAILED", "MANUAL_ACTION" -> TaskStatus.FAILED;
            case "CANCELLED" -> TaskStatus.CANCELLED;
            case "PENDING", "READY" -> TaskStatus.PENDING;
            default -> TaskStatus.RUNNING;
        };
    }

    private static Scope scope(RuntimeContext rc, String sessionId) {
        if (rc == null) {
            throw new IllegalArgumentException("RuntimeContext is required");
        }
        TenantContext tenant = TenantContext.from(rc);
        if (tenant == null) {
            throw new IllegalArgumentException("TenantContext is required");
        }
        return new Scope(
                tenant,
                UUID.fromString(tenant.orgId()),
                UUID.fromString(tenant.userId()),
                UUID.fromString(required(sessionId, "sessionId")));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private RunOrchestrationService.SubagentPolicy subagentPolicy() {
        SaasProperties.Subagents subagents = properties.getSubagents();
        SaasProperties.Orchestration orchestration = properties.getOrchestration();
        boolean governed = orchestration.isBudgetEnforcementEnabled();
        return new RunOrchestrationService.SubagentPolicy(
                subagents.getMaxDepth(),
                subagents.getMaxChildrenPerAgent(),
                subagents.getMaxTasksPerRun(),
                governed ? Math.max(0, orchestration.getMaxTaskTokens()) : 0,
                governed ? Math.max(0, orchestration.getMaxTaskCostMicros()) : 0,
                governed ? Math.max(0, orchestration.getMaxTaskModelCalls()) : 0,
                governed ? Math.max(0, orchestration.getMaxTaskDurationSeconds()) : 0);
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be a UUID", e);
        }
    }

    private record Scope(TenantContext tenant, UUID orgId, UUID userId, UUID sessionId) {
        private TaskScope taskScope() {
            return new TaskScope(orgId, userId, sessionId);
        }
    }
}
