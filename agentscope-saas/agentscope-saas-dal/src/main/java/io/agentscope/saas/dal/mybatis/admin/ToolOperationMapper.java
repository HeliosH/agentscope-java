/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative mapper for atomic tool execution journal transitions. */
public interface ToolOperationMapper {
    @Insert(
            """
            INSERT INTO tool_operations
                (id, org_id, run_id, task_id, agent_run_id, attempt_id, operation_key,
                 invocation_id, step_id, tool_call_id, tool_name, input_hash, input_json,
                 retry_safety, status, prepared_at, updated_at)
            SELECT #{id}, #{orgId}, #{runId}, #{taskId}, #{agentRunId}, #{attemptId},
                   #{operationKey}, #{invocationId}, #{stepId}, #{toolCallId}, #{toolName},
                   #{inputHash}, CAST(#{inputJson} AS JSON), #{retrySafety}, 'CLAIMED',
                   #{preparedAt}, #{preparedAt}
              FROM assistant_runs run
             WHERE run.id = #{runId}
               AND run.org_id = #{orgId}
               AND (CAST(#{taskId} AS VARCHAR) IS NULL OR EXISTS (
                       SELECT 1 FROM task_nodes task
                        WHERE task.id = #{taskId}
                          AND task.org_id = #{orgId}
                          AND task.run_id = #{runId}))
               AND (CAST(#{agentRunId} AS VARCHAR) IS NULL OR EXISTS (
                       SELECT 1 FROM agent_runs agent_run
                        WHERE agent_run.id = #{agentRunId}
                          AND agent_run.org_id = #{orgId}
                          AND agent_run.run_id = #{runId}
                          AND (CAST(#{taskId} AS VARCHAR) IS NULL
                               OR agent_run.task_id = #{taskId})))
               AND (CAST(#{attemptId} AS VARCHAR) IS NULL OR EXISTS (
                       SELECT 1 FROM run_attempts attempt
                        WHERE attempt.id = #{attemptId}
                          AND attempt.org_id = #{orgId}
                          AND attempt.run_id = #{runId}
                          AND (CAST(#{taskId} AS VARCHAR) IS NULL
                               OR attempt.task_id = #{taskId})
                          AND (CAST(#{agentRunId} AS VARCHAR) IS NULL
                               OR attempt.agent_run_id = #{agentRunId})))
            """)
    int insertClaimed(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("agentRunId") UUID agentRunId,
            @Param("attemptId") UUID attemptId,
            @Param("operationKey") String operationKey,
            @Param("invocationId") UUID invocationId,
            @Param("stepId") String stepId,
            @Param("toolCallId") String toolCallId,
            @Param("toolName") String toolName,
            @Param("inputHash") String inputHash,
            @Param("inputJson") String inputJson,
            @Param("retrySafety") String retrySafety,
            @Param("preparedAt") OffsetDateTime preparedAt);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, agent_run_id, attempt_id, operation_key,
                   invocation_id, step_id, tool_call_id, tool_name, input_hash,
                   CAST(input_json AS VARCHAR) AS input_json, retry_safety, status,
                   CAST(result_json AS VARCHAR) AS result_json, error_type, error_message,
                   prepared_at, started_at, completed_at, updated_at, version
              FROM tool_operations
             WHERE org_id = #{orgId} AND run_id = #{runId} AND operation_key = #{operationKey}
             FOR UPDATE
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "attempt_id", javaType = UUID.class),
        @Arg(column = "operation_key", javaType = String.class),
        @Arg(column = "invocation_id", javaType = UUID.class),
        @Arg(column = "step_id", javaType = String.class),
        @Arg(column = "tool_call_id", javaType = String.class),
        @Arg(column = "tool_name", javaType = String.class),
        @Arg(column = "input_hash", javaType = String.class),
        @Arg(column = "input_json", javaType = String.class),
        @Arg(column = "retry_safety", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "result_json", javaType = String.class),
        @Arg(column = "error_type", javaType = String.class),
        @Arg(column = "error_message", javaType = String.class),
        @Arg(column = "prepared_at", javaType = OffsetDateTime.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class),
        @Arg(column = "updated_at", javaType = OffsetDateTime.class),
        @Arg(column = "version", javaType = long.class)
    })
    List<ToolOperationData> lock(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("operationKey") String operationKey);

    @Select(
            """
            SELECT COUNT(*)
              FROM run_attempts attempt
             WHERE attempt.id = #{attemptId}
               AND attempt.org_id = #{orgId}
               AND attempt.run_id = #{runId}
               AND (CAST(#{taskId} AS VARCHAR) IS NULL OR attempt.task_id = #{taskId})
               AND (CAST(#{agentRunId} AS VARCHAR) IS NULL
                    OR attempt.agent_run_id = #{agentRunId})
               AND attempt.status = 'RUNNING'
               AND ((attempt.lease_owner = #{leaseOwner}
                     AND attempt.lease_expires_at >= #{now})
                    OR (attempt.lease_owner IS NULL
                        AND attempt.lease_expires_at IS NULL
                        AND attempt.idempotency_key = #{leaseOwner}))
            """)
    int countActiveExecutionScope(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("agentRunId") UUID agentRunId,
            @Param("attemptId") UUID attemptId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE tool_operations
               SET invocation_id = #{invocationId}, retry_safety = #{retrySafety},
                   attempt_id = #{attemptId}, step_id = #{stepId},
                   status = 'CLAIMED', started_at = NULL, completed_at = NULL,
                   result_json = NULL, error_type = NULL, error_message = NULL,
                   updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND org_id = #{orgId} AND version = #{expectedVersion}
               AND status IN ('CLAIMED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN')
            """)
    int reclaim(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("expectedVersion") long expectedVersion,
            @Param("invocationId") UUID invocationId,
            @Param("attemptId") UUID attemptId,
            @Param("stepId") String stepId,
            @Param("retrySafety") String retrySafety,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE tool_operations
               SET status = 'RUNNING', started_at = #{now}, updated_at = #{now},
                   version = version + 1
             WHERE id = #{id} AND org_id = #{orgId} AND invocation_id = #{invocationId}
               AND status = 'CLAIMED'
               AND EXISTS (
                   SELECT 1
                     FROM run_attempts attempt
                    WHERE attempt.id = tool_operations.attempt_id
                      AND attempt.status = 'RUNNING'
                      AND ((attempt.lease_owner = #{leaseOwner}
                            AND attempt.lease_expires_at >= #{now})
                           OR (attempt.lease_owner IS NULL
                               AND attempt.lease_expires_at IS NULL
                               AND attempt.idempotency_key = #{leaseOwner})))
            """)
    int markRunning(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("invocationId") UUID invocationId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE tool_operations
               SET status = #{status}, result_json = CAST(#{resultJson} AS JSON),
                   error_type = #{errorType}, error_message = #{errorMessage},
                   completed_at = #{now}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND org_id = #{orgId} AND invocation_id = #{invocationId}
               AND status = 'RUNNING'
               AND EXISTS (
                   SELECT 1
                     FROM run_attempts attempt
                    WHERE attempt.id = tool_operations.attempt_id
                      AND attempt.status = 'RUNNING'
                      AND ((attempt.lease_owner = #{leaseOwner}
                            AND attempt.lease_expires_at >= #{now})
                           OR (attempt.lease_owner IS NULL
                               AND attempt.lease_expires_at IS NULL
                               AND attempt.idempotency_key = #{leaseOwner})))
            """)
    int complete(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("invocationId") UUID invocationId,
            @Param("status") String status,
            @Param("resultJson") String resultJson,
            @Param("errorType") String errorType,
            @Param("errorMessage") String errorMessage,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE tool_operations
               SET status = 'OUTCOME_UNKNOWN', result_json = NULL,
                   error_type = #{errorType}, error_message = #{errorMessage},
                   completed_at = #{now}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND org_id = #{orgId} AND version = #{expectedVersion}
               AND status = 'RUNNING'
               AND NOT EXISTS (
                   SELECT 1
                     FROM run_attempts attempt
                    WHERE attempt.id = tool_operations.attempt_id
                      AND attempt.status = 'RUNNING'
                      AND ((attempt.lease_owner IS NULL
                            AND attempt.lease_expires_at IS NULL)
                           OR (attempt.lease_owner IS NOT NULL
                               AND attempt.lease_expires_at >= #{now})))
            """)
    int markOutcomeUnknownIfAttemptInactive(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("expectedVersion") long expectedVersion,
            @Param("errorType") String errorType,
            @Param("errorMessage") String errorMessage,
            @Param("now") OffsetDateTime now);

    @Insert(
            """
            INSERT INTO orchestration_outbox
                (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
            VALUES (#{outboxId}, #{orgId}, #{operationId}, 'tool_operation', #{eventType},
                    CAST(#{payloadJson} AS JSON))
            """)
    int appendTerminalOutbox(
            @Param("outboxId") UUID outboxId,
            @Param("orgId") UUID orgId,
            @Param("operationId") UUID operationId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson);
}
