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

/** Administrative MyBatis mapper for the cross-tenant durable task worker. */
public interface DurableTaskLeaseMapper {

    @Select(
            """
            SELECT t.id AS task_id, t.org_id, t.run_id, r.user_id, r.agent_id, r.session_id,
                   ar.id AS agent_run_id, COALESCE(ar.agent_type, 'assistant') AS agent_type,
                   t.sub_session_id,
                   u.role, u.tier, COALESCE(p.max_sandboxes, 1) AS max_sandboxes,
                   COALESCE(p.monthly_token_quota, 0) AS token_quota,
                   t.title, t.input_json, t.workspace_mode, t.max_attempts,
                   t.retry_mode, t.retry_base_seconds,
                   COALESCE((SELECT MAX(a.attempt_no) FROM run_attempts a
                              WHERE a.task_id = t.id), 0) AS last_attempt_no
              FROM task_nodes t
              JOIN assistant_runs r ON r.id = t.run_id
              LEFT JOIN agent_runs ar ON ar.id = t.owner_agent_run_id
              JOIN users u ON u.id = r.user_id
              LEFT JOIN tier_policies p ON p.tier = u.tier
             WHERE t.status = 'READY'
               AND r.status = 'RUNNING'
               AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= #{readyAt})
               AND NOT EXISTS (
                   SELECT 1 FROM task_edges e
                   JOIN task_nodes dependency ON dependency.id = e.from_task_id
                    WHERE e.to_task_id = t.id AND dependency.status <> 'SUCCEEDED'
               )
             ORDER BY t.priority DESC, t.created_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "session_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "sub_session_id", javaType = String.class),
        @Arg(column = "role", javaType = String.class),
        @Arg(column = "tier", javaType = String.class),
        @Arg(column = "max_sandboxes", javaType = int.class),
        @Arg(column = "token_quota", javaType = long.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "input_json", javaType = String.class),
        @Arg(column = "workspace_mode", javaType = String.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "retry_mode", javaType = String.class),
        @Arg(column = "retry_base_seconds", javaType = int.class),
        @Arg(column = "last_attempt_no", javaType = int.class)
    })
    List<TaskLeaseCandidateData> findReadyCandidates(
            @Param("readyAt") OffsetDateTime readyAt, @Param("limit") int limit);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'CLAIMED', next_attempt_at = NULL, updated_at = #{claimedAt}
             WHERE id = #{taskId}
               AND status = 'READY'
               AND (next_attempt_at IS NULL OR next_attempt_at <= #{claimedAt})
            """)
    int claimTask(@Param("taskId") UUID taskId, @Param("claimedAt") OffsetDateTime claimedAt);

    @Insert(
            """
            INSERT INTO run_attempts
                (id, org_id, run_id, task_id, agent_run_id, attempt_no, status, lease_owner,
                 lease_expires_at, heartbeat_at, idempotency_key, updated_at)
            VALUES
                (#{attemptId}, #{orgId}, #{runId}, #{taskId}, #{agentRunId}, #{attemptNo},
                 'LEASED', #{workerId}, #{leaseExpiresAt}, #{heartbeatAt}, #{idempotencyKey},
                 #{heartbeatAt})
            """)
    int createAttempt(
            @Param("attemptId") UUID attemptId,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("agentRunId") UUID agentRunId,
            @Param("attemptNo") int attemptNo,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt,
            @Param("heartbeatAt") OffsetDateTime heartbeatAt,
            @Param("idempotencyKey") String idempotencyKey);

    @Select(
            """
            SELECT a.id AS attempt_id, a.org_id, a.run_id, a.task_id, a.agent_run_id,
                   a.attempt_no, t.max_attempts, t.retry_mode, t.retry_base_seconds
              FROM run_attempts a
              JOIN task_nodes t ON t.id = a.task_id
             WHERE a.id = #{attemptId} AND a.lease_owner = #{workerId}
            """)
    @ConstructorArgs({
        @Arg(column = "attempt_id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "attempt_no", javaType = int.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "retry_mode", javaType = String.class),
        @Arg(column = "retry_base_seconds", javaType = int.class)
    })
    List<TaskLeaseAttemptData> findAttempt(
            @Param("attemptId") UUID attemptId, @Param("workerId") String workerId);

    @Update(
            """
            UPDATE run_attempts
               SET status = 'RUNNING', started_at = COALESCE(started_at, #{startedAt}),
                   heartbeat_at = #{startedAt}, lease_expires_at = #{leaseExpiresAt},
                   updated_at = #{startedAt}
             WHERE id = #{attemptId}
               AND lease_owner = #{workerId}
               AND status = 'LEASED'
               AND lease_expires_at >= #{startedAt}
            """)
    int startAttempt(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'RUNNING', updated_at = #{updatedAt}
             WHERE id = #{taskId} AND status = 'CLAIMED'
            """)
    int markTaskRunning(@Param("taskId") UUID taskId, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE agent_runs
               SET status = 'RUNNING', updated_at = #{updatedAt}
             WHERE id = #{agentRunId} AND status IN ('READY', 'CLAIMED')
            """)
    int markAgentRunRunning(
            @Param("agentRunId") UUID agentRunId, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE run_attempts
               SET heartbeat_at = #{heartbeatAt}, lease_expires_at = #{leaseExpiresAt},
                   updated_at = #{heartbeatAt}
             WHERE id = #{attemptId}
               AND lease_owner = #{workerId}
               AND status IN ('LEASED', 'RUNNING')
               AND lease_expires_at >= #{heartbeatAt}
            """)
    int heartbeat(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("heartbeatAt") OffsetDateTime heartbeatAt,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    @Select(
            """
            SELECT id AS attempt_id, lease_owner AS worker_id
              FROM run_attempts
             WHERE status IN ('LEASED', 'RUNNING')
               AND lease_expires_at < #{expiredBefore}
             ORDER BY lease_expires_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "attempt_id", javaType = UUID.class),
        @Arg(column = "worker_id", javaType = String.class)
    })
    List<ExpiredTaskLeaseData> findExpiredAttempts(
            @Param("expiredBefore") OffsetDateTime expiredBefore, @Param("limit") int limit);

    @Update(
            """
            <script>
            UPDATE run_attempts
               SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage},
                   completed_at = #{completedAt}, lease_expires_at = NULL,
                   updated_at = #{completedAt}
             WHERE id = #{attemptId}
               AND lease_owner = #{workerId}
               AND status IN ('LEASED', 'RUNNING')
            <if test="requireExpiredBefore != null">
               AND lease_expires_at &lt; #{requireExpiredBefore}
            </if>
            </script>
            """)
    int finishAttempt(
            @Param("attemptId") UUID attemptId,
            @Param("workerId") String workerId,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("requireExpiredBefore") OffsetDateTime requireExpiredBefore);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'SUCCEEDED', completed_at = #{completedAt}, updated_at = #{updatedAt},
                   output_json = CAST(#{outputJson} AS JSON), last_error_code = NULL,
                   last_error_message = NULL
             WHERE id = #{taskId} AND status IN ('CLAIMED', 'RUNNING')
            """)
    int completeTask(
            @Param("taskId") UUID taskId,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("updatedAt") OffsetDateTime updatedAt,
            @Param("outputJson") String outputJson);

    @Update(
            """
            UPDATE task_nodes target
               SET status = 'READY', updated_at = #{updatedAt}
             WHERE target.run_id = #{runId}
               AND target.status = 'PENDING'
               AND NOT EXISTS (
                   SELECT 1 FROM task_edges edge
                   JOIN task_nodes dependency ON dependency.id = edge.from_task_id
                    WHERE edge.to_task_id = target.id AND dependency.status <> 'SUCCEEDED'
               )
            """)
    int releaseReadyDependencies(
            @Param("runId") UUID runId, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET status = 'SUCCEEDED', completed_at = #{completedAt}, updated_at = #{completedAt}
             WHERE id = #{runId}
               AND status = 'RUNNING'
               AND NOT EXISTS (
                   SELECT 1 FROM task_nodes task
                    WHERE task.run_id = #{runId}
                      AND task.status NOT IN ('SUCCEEDED', 'CANCELLED')
               )
            """)
    int completeRunIfAllTasksTerminal(
            @Param("runId") UUID runId, @Param("completedAt") OffsetDateTime completedAt);

    @Select("SELECT COUNT(*) FROM task_nodes WHERE id = #{taskId} AND parent_id IS NULL")
    int countCoordinatorTasks(@Param("taskId") UUID taskId);

    @Select(
            """
            SELECT root.id AS task_id, root.owner_agent_run_id AS agent_run_id
              FROM task_nodes root
             WHERE root.run_id = #{runId}
               AND root.parent_id IS NULL
               AND root.status = 'SUCCEEDED'
               AND EXISTS (
                   SELECT 1 FROM task_nodes child
                    WHERE child.run_id = root.run_id AND child.parent_id IS NOT NULL
               )
               AND NOT EXISTS (
                   SELECT 1 FROM task_nodes child
                    WHERE child.run_id = root.run_id
                      AND child.parent_id IS NOT NULL
                      AND child.status NOT IN ('SUCCEEDED', 'CANCELLED')
               )
            """)
    @ConstructorArgs({
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class)
    })
    List<CoordinatorTaskData> findCompletedCoordinator(@Param("runId") UUID runId);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'READY', input_json = CAST(#{inputJson} AS JSON),
                   max_attempts =
                       CASE
                           WHEN max_attempts < #{minimumMaxAttempts} THEN #{minimumMaxAttempts}
                           ELSE max_attempts
                       END,
                   next_attempt_at = NULL, completed_at = NULL, updated_at = #{updatedAt}
             WHERE id = #{taskId} AND status = 'SUCCEEDED'
            """)
    int scheduleCoordinatorContinuation(
            @Param("taskId") UUID taskId,
            @Param("inputJson") String inputJson,
            @Param("minimumMaxAttempts") int minimumMaxAttempts,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE agent_runs
               SET status = 'READY', completed_at = NULL, updated_at = #{updatedAt}
             WHERE id = #{agentRunId}
            """)
    int resetAgentRun(
            @Param("agentRunId") UUID agentRunId, @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'READY', next_attempt_at = #{nextAttemptAt}, updated_at = #{updatedAt},
                   last_error_code = #{errorCode}, last_error_message = #{errorMessage}
             WHERE id = #{taskId} AND status IN ('CLAIMED', 'RUNNING')
            """)
    int scheduleTaskRetry(
            @Param("taskId") UUID taskId,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
            @Param("updatedAt") OffsetDateTime updatedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    @Update(
            """
            UPDATE task_nodes
               SET status = #{status}, completed_at = #{completedAt}, updated_at = #{completedAt},
                   last_error_code = #{errorCode}, last_error_message = #{errorMessage}
             WHERE id = #{taskId} AND status IN ('CLAIMED', 'RUNNING')
            """)
    int stopTask(
            @Param("taskId") UUID taskId,
            @Param("status") String status,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    @Update(
            """
            UPDATE assistant_runs
               SET status = 'FAILED', failure_code = #{errorCode},
                   failure_message = #{errorMessage}, completed_at = #{completedAt},
                   updated_at = #{completedAt}
             WHERE id = #{runId} AND status = 'RUNNING'
            """)
    int failRun(
            @Param("runId") UUID runId,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'CANCELLED', completed_at = #{completedAt}, updated_at = #{completedAt}
             WHERE run_id = #{runId}
               AND id <> #{taskId}
               AND status IN ('PENDING', 'READY', 'CLAIMED', 'RUNNING')
            """)
    int cancelSiblingTasks(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE run_attempts
               SET status = 'CANCELLED', completed_at = #{completedAt}, lease_expires_at = NULL,
                   updated_at = #{completedAt}
             WHERE run_id = #{runId}
               AND task_id <> #{taskId}
               AND status IN ('CREATED', 'LEASED', 'RUNNING')
            """)
    int cancelSiblingAttempts(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE agent_runs
               SET status = 'CANCELLED', completed_at = #{completedAt}, updated_at = #{completedAt}
             WHERE run_id = #{runId}
               AND task_id <> #{taskId}
               AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """)
    int cancelSiblingAgentRuns(
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE agent_runs
               SET status = #{status}, updated_at = #{updatedAt}, completed_at = #{completedAt}
             WHERE id = #{agentRunId}
            """)
    int updateAgentRun(
            @Param("agentRunId") UUID agentRunId,
            @Param("status") String status,
            @Param("updatedAt") OffsetDateTime updatedAt,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET next_event_seq = next_event_seq + 1, updated_at = #{updatedAt}
             WHERE id = #{runId}
            """)
    int incrementEventSequence(
            @Param("runId") UUID runId, @Param("updatedAt") OffsetDateTime updatedAt);

    @Select("SELECT next_event_seq FROM assistant_runs WHERE id = #{runId}")
    Long findEventSequence(@Param("runId") UUID runId);

    @Insert(
            """
            INSERT INTO run_events
                (id, org_id, user_id, run_id, task_id, agent_run_id, attempt_id, seq, event_type,
                 payload_json)
            SELECT #{eventId}, #{orgId}, r.user_id, #{runId}, #{taskId}, #{agentRunId},
                   #{attemptId}, #{sequence}, #{eventType}, CAST(#{payloadJson} AS JSON)
              FROM assistant_runs r
             WHERE r.id = #{runId}
            """)
    int appendRunEvent(
            @Param("eventId") UUID eventId,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("agentRunId") UUID agentRunId,
            @Param("attemptId") UUID attemptId,
            @Param("sequence") long sequence,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson);

    @Insert(
            """
            INSERT INTO orchestration_outbox
                (id, org_id, aggregate_id, aggregate_type, event_type, payload_json)
            VALUES
                (#{eventId}, #{orgId}, #{runId}, 'assistant_run', #{eventType},
                 CAST(#{payloadJson} AS JSON))
            """)
    int appendOutbox(
            @Param("eventId") UUID eventId,
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson);
}
