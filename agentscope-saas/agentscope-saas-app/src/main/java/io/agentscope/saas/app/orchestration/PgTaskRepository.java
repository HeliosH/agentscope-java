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
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed bridge for Harness background subagent tools. */
@Component
public class PgTaskRepository implements TaskRepository {

    private static final String TASK_QUERY =
            """
            SELECT t.id, t.run_id, r.agent_id, t.external_task_id, t.status, t.output_json,
                   t.last_error_message, t.created_at, t.completed_at, t.delivered_at,
                   COALESCE(ar.agent_type, 'assistant') AS agent_type
              FROM task_nodes t
              JOIN assistant_runs r ON r.id = t.run_id
              LEFT JOIN agent_runs ar ON ar.id = t.owner_agent_run_id
             WHERE t.org_id = ? AND r.user_id = ? AND r.session_id = ?
               AND t.external_task_id IS NOT NULL
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RunOrchestrationService orchestration;
    private final SaasProperties properties;

    public PgTaskRepository(
            @Qualifier("adminDataSource") DataSource dataSource,
            ObjectMapper objectMapper,
            RunOrchestrationService orchestration,
            SaasProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.orchestration = orchestration;
        this.properties = properties;
    }

    @Override
    public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        List<TaskRow> rows =
                jdbc.query(
                        TASK_QUERY
                                + " AND t.external_task_id = ? ORDER BY t.created_at DESC LIMIT 1",
                        this::mapRow,
                        scope.orgId(),
                        scope.userId(),
                        scope.sessionId(),
                        required(taskId, "taskId"));
        return rows.isEmpty() ? null : toBackgroundTask(rows.get(0));
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
            inputJson =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "prompt", local.input(),
                                    "externalTaskId", taskId,
                                    "subSessionId", local.subSessionId()));
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
        return jdbc
                .query(
                        TASK_QUERY + " ORDER BY t.created_at ASC",
                        this::mapRow,
                        scope.orgId(),
                        scope.userId(),
                        scope.sessionId())
                .stream()
                .filter(row -> filter == null || taskStatus(row.status()) == filter)
                .map(this::toBackgroundTask)
                .toList();
    }

    @Override
    public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        TaskRow row = findRow(scope, taskId);
        if (row == null || taskStatus(row.status()).isTerminal()) {
            return row != null;
        }
        return orchestration.cancelSubagentTask(
                scope.tenant(), row.agentId(), row.runId(), row.id());
    }

    @Override
    public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        Scope scope = scope(rc, sessionId);
        return jdbc
                .query(
                        TASK_QUERY
                                + " AND t.status IN"
                                + " ('SUCCEEDED','FAILED','CANCELLED','MANUAL_ACTION') AND"
                                + " t.delivered_at IS NULL ORDER BY t.completed_at ASC",
                        this::mapRow,
                        scope.orgId(),
                        scope.userId(),
                        scope.sessionId())
                .stream()
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
        jdbc.update(
                "UPDATE task_nodes SET delivered_at = COALESCE(delivered_at, ?) WHERE id IN ("
                        + "SELECT t.id FROM task_nodes t JOIN assistant_runs r ON r.id = t.run_id "
                        + "WHERE t.org_id = ? AND r.user_id = ? AND r.session_id = ? "
                        + "AND t.external_task_id = ?)",
                OffsetDateTime.now(),
                scope.orgId(),
                scope.userId(),
                scope.sessionId(),
                required(taskId, "taskId"));
    }

    @Override
    public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        Scope scope = scope(rc, sessionId);
        TaskRow row = findRow(scope, taskId);
        return row != null && row.deliveredAt() != null;
    }

    private TaskRow findRow(Scope scope, String taskId) {
        List<TaskRow> rows =
                jdbc.query(
                        TASK_QUERY
                                + " AND t.external_task_id = ? ORDER BY t.created_at DESC LIMIT 1",
                        this::mapRow,
                        scope.orgId(),
                        scope.userId(),
                        scope.sessionId(),
                        required(taskId, "taskId"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getString("external_task_id"),
                rs.getString("agent_type"),
                rs.getString("status"),
                rs.getString("output_json"),
                rs.getString("last_error_message"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("delivered_at", OffsetDateTime.class));
    }

    private BackgroundTask toBackgroundTask(TaskRow row) {
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

    private String result(TaskRow row) {
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
        return new RunOrchestrationService.SubagentPolicy(
                subagents.getMaxDepth(),
                subagents.getMaxChildrenPerAgent(),
                subagents.getMaxTasksPerRun());
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

    private record Scope(TenantContext tenant, UUID orgId, UUID userId, UUID sessionId) {}

    private record TaskRow(
            UUID id,
            UUID runId,
            UUID agentId,
            String externalTaskId,
            String agentType,
            String status,
            String outputJson,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            OffsetDateTime deliveredAt) {}
}
