/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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

/** MyBatis mapper for atomic orchestration governance persistence. */
public interface OrchestrationGovernanceMapper {

    @Select(
            """
            SELECT CASE
                     WHEN r.deadline_at IS NULL THEN t.deadline_at
                     WHEN t.deadline_at IS NULL THEN r.deadline_at
                     WHEN r.deadline_at < t.deadline_at THEN r.deadline_at
                     ELSE t.deadline_at
                   END AS effective_deadline
              FROM assistant_runs r
              JOIN agent_runs ar ON ar.run_id = r.id
              JOIN task_nodes t ON t.id = ar.task_id
             WHERE r.id = #{runId} AND r.org_id = #{orgId} AND ar.id = #{agentRunId}
            """)
    List<OffsetDateTime> findEffectiveDeadline(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("agentRunId") UUID agentRunId);

    @Select(
            """
            SELECT r.org_id, r.id AS run_id, ar.id AS agent_run_id
              FROM assistant_runs r
              JOIN task_nodes t ON t.run_id = r.id
              JOIN agent_runs ar ON ar.task_id = t.id
             WHERE r.status = 'RUNNING'
               AND (r.deadline_at <= CURRENT_TIMESTAMP
                    OR t.deadline_at <= CURRENT_TIMESTAMP)
             ORDER BY COALESCE(t.deadline_at, r.deadline_at), r.created_at
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class)
    })
    List<OrchestrationBudgetScopeData> findExpiredScopes(@Param("limit") int limit);

    @Select(
            """
            SELECT permission_snapshot_json AS json, permission_snapshot_hash AS hash
              FROM agent_runs
             WHERE id = #{agentRunId} AND run_id = #{runId} AND org_id = #{orgId}
            """)
    @ConstructorArgs({
        @Arg(column = "json", javaType = String.class),
        @Arg(column = "hash", javaType = String.class)
    })
    List<PermissionSnapshotData> findPermissionSnapshot(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("agentRunId") UUID agentRunId);

    @Select(
            """
            SELECT r.id AS run_id, r.org_id, r.status AS run_status,
                   r.token_budget AS run_token_budget,
                   r.consumed_tokens AS run_consumed_tokens,
                   r.cost_budget_micros AS run_cost_budget,
                   r.consumed_cost_micros AS run_consumed_cost,
                   r.model_call_budget AS run_call_budget,
                   r.consumed_model_calls AS run_consumed_calls,
                   r.deadline_at AS run_deadline,
                   t.id AS task_id, t.token_budget AS task_token_budget,
                   t.consumed_tokens AS task_consumed_tokens,
                   t.cost_budget_micros AS task_cost_budget,
                   t.consumed_cost_micros AS task_consumed_cost,
                   t.model_call_budget AS task_call_budget,
                   t.consumed_model_calls AS task_consumed_calls,
                   t.deadline_at AS task_deadline
              FROM assistant_runs r
              JOIN agent_runs ar ON ar.run_id = r.id
              JOIN task_nodes t ON t.id = ar.task_id
             WHERE r.id = #{runId} AND r.org_id = #{orgId} AND ar.id = #{agentRunId}
             FOR UPDATE
            """)
    @ConstructorArgs({
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_status", javaType = String.class),
        @Arg(column = "run_token_budget", javaType = Long.class),
        @Arg(column = "run_consumed_tokens", javaType = long.class),
        @Arg(column = "run_cost_budget", javaType = Long.class),
        @Arg(column = "run_consumed_cost", javaType = long.class),
        @Arg(column = "run_call_budget", javaType = Integer.class),
        @Arg(column = "run_consumed_calls", javaType = int.class),
        @Arg(column = "run_deadline", javaType = OffsetDateTime.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "task_token_budget", javaType = Long.class),
        @Arg(column = "task_consumed_tokens", javaType = long.class),
        @Arg(column = "task_cost_budget", javaType = Long.class),
        @Arg(column = "task_consumed_cost", javaType = long.class),
        @Arg(column = "task_call_budget", javaType = Integer.class),
        @Arg(column = "task_consumed_calls", javaType = int.class),
        @Arg(column = "task_deadline", javaType = OffsetDateTime.class)
    })
    List<OrchestrationBudgetData> lockBudget(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("agentRunId") UUID agentRunId);

    @Update(
            """
            UPDATE assistant_runs
               SET consumed_tokens = consumed_tokens + #{tokenDelta},
                   consumed_cost_micros = consumed_cost_micros + #{costDelta},
                   consumed_model_calls = consumed_model_calls + #{modelCallDelta},
                   updated_at = #{now}
             WHERE id = #{runId} AND org_id = #{orgId} AND status = 'RUNNING'
            """)
    int recordRunUsage(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("now") OffsetDateTime now,
            @Param("tokenDelta") long tokenDelta,
            @Param("costDelta") long costDelta,
            @Param("modelCallDelta") int modelCallDelta);

    @Update(
            """
            UPDATE task_nodes
               SET consumed_tokens = consumed_tokens + #{tokenDelta},
                   consumed_cost_micros = consumed_cost_micros + #{costDelta},
                   consumed_model_calls = consumed_model_calls + #{modelCallDelta},
                   updated_at = #{now}
             WHERE id = #{taskId} AND org_id = #{orgId}
            """)
    int recordTaskUsage(
            @Param("taskId") UUID taskId,
            @Param("orgId") UUID orgId,
            @Param("now") OffsetDateTime now,
            @Param("tokenDelta") long tokenDelta,
            @Param("costDelta") long costDelta,
            @Param("modelCallDelta") int modelCallDelta);

    @Update(
            """
            UPDATE assistant_runs
               SET status = 'FAILED', failure_code = #{reason}, failure_message = #{message},
                   completed_at = #{now}, updated_at = #{now}
             WHERE id = #{runId} AND org_id = #{orgId} AND status = 'RUNNING'
            """)
    int failRun(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("now") OffsetDateTime now,
            @Param("reason") String reason,
            @Param("message") String message);

    @Update(
            """
            UPDATE task_nodes
               SET status = CASE WHEN id = #{taskId} THEN 'FAILED' ELSE 'CANCELLED' END,
                   last_error_code =
                       CASE WHEN id = #{taskId} THEN #{reason} ELSE last_error_code END,
                   last_error_message =
                       CASE WHEN id = #{taskId} THEN #{message} ELSE last_error_message END,
                   completed_at = #{now}, updated_at = #{now}
             WHERE run_id = #{runId} AND status IN ('PENDING','READY','CLAIMED','RUNNING')
            """)
    int failTasks(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("now") OffsetDateTime now,
            @Param("reason") String reason,
            @Param("message") String message);

    @Update(
            """
            UPDATE agent_runs
               SET status = CASE WHEN task_id = #{taskId} THEN 'FAILED' ELSE 'CANCELLED' END,
                   completed_at = #{now}, updated_at = #{now}
             WHERE run_id = #{runId} AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')
            """)
    int failAgentRuns(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE run_attempts
               SET status = CASE WHEN task_id = #{taskId} THEN 'FAILED' ELSE 'CANCELLED' END,
                   error_code = CASE WHEN task_id = #{taskId} THEN #{reason} ELSE error_code END,
                   error_message =
                       CASE WHEN task_id = #{taskId} THEN #{message} ELSE error_message END,
                   lease_expires_at = NULL, completed_at = #{now}, updated_at = #{now}
             WHERE run_id = #{runId} AND status IN ('CREATED','LEASED','RUNNING')
            """)
    int failAttempts(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("now") OffsetDateTime now,
            @Param("reason") String reason,
            @Param("message") String message);

    @Update(
            """
            UPDATE assistant_runs
               SET next_event_seq = next_event_seq + 1, updated_at = #{now}
             WHERE id = #{runId}
            """)
    int incrementEventSequence(@Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    @Select("SELECT next_event_seq FROM assistant_runs WHERE id = #{runId}")
    Long findEventSequence(@Param("runId") UUID runId);

    @Insert(
            """
            INSERT INTO run_events
                (id, org_id, user_id, run_id, task_id, seq, event_type, payload_json)
            SELECT #{eventId}, #{orgId}, r.user_id, #{runId}, #{taskId}, #{sequence},
                   'RUN_BUDGET_EXCEEDED', CAST(#{payloadJson} AS JSON)
              FROM assistant_runs r WHERE r.id = #{runId}
            """)
    int insertBudgetExceededEvent(
            @Param("eventId") UUID eventId,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("sequence") long sequence,
            @Param("payloadJson") String payloadJson);

    @Insert(
            """
            INSERT INTO orchestration_outbox
                (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
            VALUES (#{outboxId}, #{orgId}, #{runId}, 'assistant_run',
                    'RUN_BUDGET_EXCEEDED', CAST(#{envelopeJson} AS JSON))
            """)
    int insertBudgetExceededOutbox(
            @Param("outboxId") UUID outboxId,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("envelopeJson") String envelopeJson);
}
