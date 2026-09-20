/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.orchestration.ContextCheckpointRepository.NewCheckpoint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Administrative mapper for lease-fenced context checkpoints. */
public interface ContextCheckpointMapper {

    @Select(
            """
            SELECT attempt.id
              FROM run_attempts attempt
             WHERE attempt.id = #{attemptId}
               AND attempt.org_id = #{orgId}
               AND attempt.run_id = #{runId}
               AND attempt.task_id = #{taskId}
               AND attempt.agent_run_id = #{agentRunId}
               AND attempt.status = 'RUNNING'
               AND ((attempt.lease_owner = #{leaseOwner}
                     AND attempt.lease_expires_at >= #{now})
                    OR (attempt.lease_owner IS NULL
                        AND attempt.lease_expires_at IS NULL
                        AND attempt.idempotency_key = #{leaseOwner}))
             FOR UPDATE
            """)
    List<UUID> lockActiveScope(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("agentRunId") UUID agentRunId,
            @Param("attemptId") UUID attemptId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT COALESCE(MAX(history_revision), 0)
              FROM context_checkpoints
             WHERE org_id = #{orgId} AND run_id = #{runId} AND agent_run_id = #{agentRunId}
            """)
    long latestRevision(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("agentRunId") UUID agentRunId);

    @Insert(
            """
            INSERT INTO context_checkpoints
                (id, org_id, run_id, task_id, agent_run_id, attempt_id, history_revision,
                 step_id, history_hash, summary, retained_tail_json, retained_facts_version,
                 pending_operations_json, workspace_version, created_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{taskId}, #{agentRunId}, #{attemptId},
                 #{historyRevision}, #{stepId}, #{historyHash}, #{summary},
                 CAST(#{retainedTailJson} AS JSON), #{retainedFactsVersion},
                 CAST(#{pendingOperationsJson} AS JSON), #{workspaceVersion}, #{createdAt})
            """)
    int insert(NewCheckpoint checkpoint);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, agent_run_id, attempt_id, history_revision,
                   step_id, history_hash, summary,
                   CAST(retained_tail_json AS VARCHAR) AS retained_tail_json,
                   retained_facts_version,
                   CAST(pending_operations_json AS VARCHAR) AS pending_operations_json,
                   workspace_version, created_at
              FROM context_checkpoints
             WHERE org_id = #{orgId} AND run_id = #{runId} AND agent_run_id = #{agentRunId}
             ORDER BY history_revision DESC
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "attempt_id", javaType = UUID.class),
        @Arg(column = "history_revision", javaType = long.class),
        @Arg(column = "step_id", javaType = String.class),
        @Arg(column = "history_hash", javaType = String.class),
        @Arg(column = "summary", javaType = String.class),
        @Arg(column = "retained_tail_json", javaType = String.class),
        @Arg(column = "retained_facts_version", javaType = String.class),
        @Arg(column = "pending_operations_json", javaType = String.class),
        @Arg(column = "workspace_version", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class)
    })
    List<ContextCheckpointData> findLatest(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("agentRunId") UUID agentRunId);
}
