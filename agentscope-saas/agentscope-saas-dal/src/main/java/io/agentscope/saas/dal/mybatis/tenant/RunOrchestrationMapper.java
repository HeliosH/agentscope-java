/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AssistantRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewTask;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.RunAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.RunEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.TaskNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant MyBatis mapper for the durable Run aggregate. */
public interface RunOrchestrationMapper {

    String RUN_COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, session_id, mode, status, cancel_requested,
                   failure_code, failure_message, token_budget, consumed_tokens,
                   cost_budget_micros, consumed_cost_micros, model_call_budget,
                   consumed_model_calls, deadline_at, created_at, started_at, completed_at
              FROM assistant_runs
            """;

    @Select(
            RUN_COLUMNS
                    + """
                     WHERE id = #{runId} AND org_id = #{orgId}
                       AND user_id = #{userId} AND agent_id = #{agentId}
                    """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "session_id", javaType = UUID.class),
        @Arg(column = "mode", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "cancel_requested", javaType = boolean.class),
        @Arg(column = "failure_code", javaType = String.class),
        @Arg(column = "failure_message", javaType = String.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<AssistantRun> findOwnedRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Select(
            RUN_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND agent_id = #{agentId}
                       AND idempotency_key = #{idempotencyKey}
                     LIMIT 1
                    """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "session_id", javaType = UUID.class),
        @Arg(column = "mode", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "cancel_requested", javaType = boolean.class),
        @Arg(column = "failure_code", javaType = String.class),
        @Arg(column = "failure_message", javaType = String.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<AssistantRun> findByIdempotencyKey(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select(
            RUN_COLUMNS
                    + """
                     WHERE id = #{runId} AND org_id = #{orgId}
                       AND user_id = #{userId} AND agent_id = #{agentId}
                     FOR UPDATE
                    """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "session_id", javaType = UUID.class),
        @Arg(column = "mode", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "cancel_requested", javaType = boolean.class),
        @Arg(column = "failure_code", javaType = String.class),
        @Arg(column = "failure_message", javaType = String.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<AssistantRun> lockOwnedRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Insert(
            """
            INSERT INTO assistant_runs
                (id, org_id, user_id, agent_id, session_id, trigger_message_id, idempotency_key,
                 mode, status, cancel_requested, next_event_seq, token_budget,
                 cost_budget_micros, model_call_budget, deadline_at, started_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{triggerMessageId},
                 #{idempotencyKey}, #{mode}, #{status}, FALSE, 0, #{tokenBudget},
                 #{costBudgetMicros}, #{modelCallBudget}, #{deadlineAt}, #{startedAt}, #{updatedAt})
            """)
    int insertRun(NewRun run);

    @Update(
            """
            UPDATE assistant_runs
               SET status = #{status}, cancel_requested = #{cancelRequested},
                   failure_code = #{failureCode}, failure_message = #{failureMessage},
                   completed_at = #{completedAt}, updated_at = #{updatedAt},
                   version = version + 1
             WHERE id = #{runId} AND org_id = #{orgId}
            """)
    int completeRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("cancelRequested") boolean cancelRequested,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{runId} AND org_id = #{orgId}
            """)
    int touchRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET completed_at = NULL, updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{runId} AND org_id = #{orgId}
            """)
    int reopenRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET trigger_message_id = NULL
             WHERE session_id = #{sessionId}
               AND org_id = #{orgId}
               AND trigger_message_id IS NOT NULL
            """)
    int clearTriggerMessageReferences(
            @Param("sessionId") UUID sessionId, @Param("orgId") UUID orgId);

    @Update(
            """
            UPDATE chat_messages
               SET source_run_id = NULL
             WHERE org_id = #{orgId}
               AND source_run_id IN (
                   SELECT id
                     FROM assistant_runs
                    WHERE session_id = #{sessionId}
                      AND org_id = #{orgId}
               )
            """)
    int clearSourceRunReferences(@Param("sessionId") UUID sessionId, @Param("orgId") UUID orgId);

    @Delete(
            """
            DELETE FROM assistant_runs
             WHERE session_id = #{sessionId}
               AND org_id = #{orgId}
            """)
    int deleteBySessionId(@Param("sessionId") UUID sessionId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, org_id, run_id, parent_id, owner_agent_run_id, external_task_id,
                   title, task_type, status, input_json, workspace_mode, max_attempts,
                   token_budget, consumed_tokens, cost_budget_micros, consumed_cost_micros,
                   model_call_budget, consumed_model_calls, deadline_at, created_at, completed_at
              FROM task_nodes
             WHERE run_id = #{runId} AND org_id = #{orgId}
             ORDER BY created_at ASC
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "parent_id", javaType = UUID.class),
        @Arg(column = "owner_agent_run_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "task_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "input_json", javaType = String.class),
        @Arg(column = "workspace_mode", javaType = String.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<TaskNode> findTasks(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, org_id, run_id, parent_id, owner_agent_run_id, external_task_id,
                   title, task_type, status, input_json, workspace_mode, max_attempts,
                   token_budget, consumed_tokens, cost_budget_micros, consumed_cost_micros,
                   model_call_budget, consumed_model_calls, deadline_at, created_at, completed_at
              FROM task_nodes
             WHERE id = #{taskId} AND run_id = #{runId} AND org_id = #{orgId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "parent_id", javaType = UUID.class),
        @Arg(column = "owner_agent_run_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "task_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "input_json", javaType = String.class),
        @Arg(column = "workspace_mode", javaType = String.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<TaskNode> findTask(
            @Param("taskId") UUID taskId, @Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, org_id, run_id, parent_id, owner_agent_run_id, external_task_id,
                   title, task_type, status, input_json, workspace_mode, max_attempts,
                   token_budget, consumed_tokens, cost_budget_micros, consumed_cost_micros,
                   model_call_budget, consumed_model_calls, deadline_at, created_at, completed_at
              FROM task_nodes
             WHERE run_id = #{runId} AND org_id = #{orgId}
               AND external_task_id = #{externalTaskId}
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "parent_id", javaType = UUID.class),
        @Arg(column = "owner_agent_run_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "task_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "input_json", javaType = String.class),
        @Arg(column = "workspace_mode", javaType = String.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "token_budget", javaType = Long.class),
        @Arg(column = "consumed_tokens", javaType = long.class),
        @Arg(column = "cost_budget_micros", javaType = Long.class),
        @Arg(column = "consumed_cost_micros", javaType = long.class),
        @Arg(column = "model_call_budget", javaType = Integer.class),
        @Arg(column = "consumed_model_calls", javaType = int.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<TaskNode> findTaskByExternalId(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("externalTaskId") String externalTaskId);

    @Select(
            """
            SELECT COUNT(*)
              FROM task_nodes
             WHERE run_id = #{runId}
               AND parent_id IS NOT NULL
               AND status NOT IN ('SUCCEEDED', 'CANCELLED')
            """)
    long countUnsettledChildren(@Param("runId") UUID runId);

    @Insert(
            """
            INSERT INTO task_nodes
                (id, org_id, run_id, parent_id, external_task_id, sub_session_id, title,
                 task_type, status, priority, input_json, expected_output_json, output_json,
                 acceptance_json, workspace_mode, max_attempts, retry_mode, retry_base_seconds,
                 token_budget, cost_budget_micros, model_call_budget, deadline_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{parentId}, #{externalTaskId}, #{subSessionId},
                 #{title}, #{taskType}, #{status}, #{priority},
                 #{inputJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{expectedOutputJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{outputJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{acceptanceJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{workspaceMode}, #{maxAttempts}, #{retryMode},
                 #{retryBaseSeconds}, #{tokenBudget}, #{costBudgetMicros}, #{modelCallBudget},
                 #{deadlineAt}, #{updatedAt})
            """)
    int insertTask(NewTask task);

    @Update(
            """
            UPDATE task_nodes
               SET owner_agent_run_id = #{ownerAgentRunId}, updated_at = #{updatedAt},
                   version = version + 1
             WHERE id = #{taskId} AND org_id = #{orgId}
            """)
    int assignTaskOwner(
            @Param("taskId") UUID taskId,
            @Param("orgId") UUID orgId,
            @Param("ownerAgentRunId") UUID ownerAgentRunId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = #{status}, last_error_code = #{errorCode},
                   last_error_message = #{errorMessage}, completed_at = #{completedAt},
                   updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{taskId} AND org_id = #{orgId}
            """)
    int completeTask(
            @Param("taskId") UUID taskId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'READY',
                   input_json =
                       #{inputJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   max_attempts = #{maxAttempts}, next_attempt_at = NULL, completed_at = NULL,
                   updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{taskId} AND org_id = #{orgId}
            """)
    int scheduleTaskContinuation(
            @Param("taskId") UUID taskId,
            @Param("orgId") UUID orgId,
            @Param("inputJson") String inputJson,
            @Param("maxAttempts") int maxAttempts,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, parent_agent_run_id, agent_type, status, depth,
                   permission_snapshot_json, permission_snapshot_hash
              FROM agent_runs
             WHERE run_id = #{runId} AND org_id = #{orgId}
             ORDER BY created_at ASC
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "parent_agent_run_id", javaType = UUID.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "depth", javaType = int.class),
        @Arg(column = "permission_snapshot_json", javaType = String.class),
        @Arg(column = "permission_snapshot_hash", javaType = String.class)
    })
    List<AgentRun> findAgentRuns(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO agent_runs
                (id, org_id, run_id, task_id, parent_agent_run_id, agent_type, status, depth,
                 context_policy, permission_snapshot_json, permission_snapshot_hash, updated_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{taskId}, #{parentAgentRunId}, #{agentType},
                 #{status}, #{depth}, #{contextPolicy},
                 #{permissionSnapshotJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{permissionSnapshotHash}, #{updatedAt})
            """)
    int insertAgentRun(NewAgentRun agentRun);

    @Update(
            """
            UPDATE agent_runs
               SET status = #{status}, completed_at = #{completedAt}, updated_at = #{updatedAt},
                   version = version + 1
             WHERE id = #{agentRunId} AND org_id = #{orgId}
            """)
    int updateAgentRunStatus(
            @Param("agentRunId") UUID agentRunId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, agent_run_id, attempt_no, status,
                   error_code, error_message, started_at, completed_at
              FROM run_attempts
             WHERE run_id = #{runId} AND org_id = #{orgId}
             ORDER BY attempt_no ASC
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "attempt_no", javaType = int.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "error_code", javaType = String.class),
        @Arg(column = "error_message", javaType = String.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class)
    })
    List<RunAttempt> findAttempts(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO run_attempts
                (id, org_id, run_id, task_id, agent_run_id, attempt_no, status,
                 idempotency_key, started_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{taskId}, #{agentRunId}, #{attemptNo}, #{status},
                 #{idempotencyKey}, #{startedAt}, #{updatedAt})
            """)
    int insertAttempt(NewAttempt attempt);

    @Update(
            """
            UPDATE run_attempts
               SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage},
                   lease_expires_at = NULL, completed_at = #{completedAt},
                   updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{attemptId} AND org_id = #{orgId}
            """)
    int updateAttemptStatus(
            @Param("attemptId") UUID attemptId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET next_event_seq = next_event_seq + 1, updated_at = #{updatedAt},
                   version = version + 1
             WHERE id = #{runId} AND org_id = #{orgId}
            """)
    int incrementEventSequence(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Select("SELECT next_event_seq FROM assistant_runs WHERE id = #{runId} AND org_id = #{orgId}")
    Long findEventSequence(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO run_events
                (id, org_id, user_id, run_id, task_id, seq, event_type, payload_json)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{runId}, #{taskId}, #{sequence}, #{eventType},
                 #{payloadJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertEvent(NewEvent event);

    @Insert(
            """
            INSERT INTO orchestration_outbox
                (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
            VALUES
                (#{id}, #{orgId}, #{aggregateId}, #{aggregateType}, #{eventType},
                 #{payloadJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertOutbox(NewOutboxMessage message);

    @Select(
            """
            SELECT seq, event_type, task_id, payload_json, created_at
              FROM run_events
             WHERE run_id = #{runId} AND org_id = #{orgId} AND user_id = #{userId}
               AND seq > #{afterSequence}
             ORDER BY seq ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "seq", javaType = long.class),
        @Arg(column = "event_type", javaType = String.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "payload_json", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class)
    })
    List<RunEvent> findEvents(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);
}
