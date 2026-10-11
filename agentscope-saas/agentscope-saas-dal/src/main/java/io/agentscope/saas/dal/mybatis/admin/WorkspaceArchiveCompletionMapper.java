/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Job;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative scan/settlement only, with the full immutable tenant/session scope. */
public interface WorkspaceArchiveCompletionMapper {

    @Update(
            "UPDATE workspace_archive_jobs SET result_json = NULL WHERE id IN (SELECT id FROM"
                + " workspace_archive_jobs WHERE body_id IS NULL AND result_json IS NOT NULL AND"
                + " status IN ('COMPLETED','FAILED','CANCELLED') AND deadline_at < #{before} ORDER"
                + " BY deadline_at LIMIT #{limit}) AND body_id IS NULL AND status IN"
                + " ('COMPLETED','FAILED','CANCELLED') AND deadline_at < #{before}")
    int purgeInline(@Param("before") OffsetDateTime before, @Param("limit") int limit);

    @Select(
            "SELECT * FROM workspace_archive_jobs WHERE status IN ('PENDING', 'RUNNING') AND"
                + " (next_attempt_at <= #{now} OR deadline_at <= #{now}) AND (lease_until IS NULL"
                + " OR lease_until <= #{now}) ORDER BY next_attempt_at, id LIMIT #{limit}")
    List<Job> candidates(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Select(
            "SELECT id FROM chat_sessions WHERE id = #{j.sessionId} AND org_id = #{j.orgId} AND"
                + " user_id = #{j.userId} AND agent_id = #{j.agentId} AND execution_generation ="
                + " #{j.sessionGeneration} FOR UPDATE")
    List<UUID> lockSession(@Param("j") Job job);

    @Select(
            "SELECT id FROM assistant_runs WHERE id = #{j.runId} AND org_id = #{j.orgId} AND"
                    + " user_id = #{j.userId} AND agent_id = #{j.agentId} AND session_id ="
                    + " #{j.sessionId} AND session_generation = #{j.sessionGeneration} AND status ="
                    + " 'RUNNING' AND NOT cancel_requested FOR UPDATE")
    List<UUID> lockRun(@Param("j") Job job);

    @Select(
            "SELECT a.id FROM run_attempts a WHERE a.id = #{j.attemptId} AND a.org_id = #{j.orgId}"
                    + " AND a.run_id = #{j.runId} AND a.task_id = #{j.taskId} AND a.agent_run_id ="
                    + " #{j.agentRunId} AND a.status = 'ARCHIVING' AND a.lease_owner ="
                    + " #{j.originalOwner} AND a.lease_expires_at IS NULL AND EXISTS (SELECT 1 FROM"
                    + " task_nodes t WHERE t.id = a.task_id AND t.run_id = a.run_id AND t.org_id ="
                    + " a.org_id AND t.status = 'ARCHIVING') FOR UPDATE")
    List<UUID> lockAttempt(@Param("j") Job job);

    @Select(
            """
            <script>SELECT id FROM workspace_archive_jobs WHERE id = #{j.id} AND org_id = #{j.orgId}
              AND user_id = #{j.userId} AND run_id = #{j.runId} AND task_id = #{j.taskId} AND attempt_id = #{j.attemptId}
              AND manifest_id = #{j.manifestId} AND body_id IS NOT DISTINCT FROM #{j.bodyId} AND original_owner = #{j.originalOwner}
              <choose><when test="token != null">AND status = 'RUNNING' AND token = #{token} AND lease_until > #{now} AND deadline_at > #{now}</when>
                <otherwise>AND status IN ('PENDING', 'RUNNING') AND (lease_until IS NULL OR lease_until &lt;= #{now}) AND (deadline_at &lt;= #{now} OR attempts >= #{maxAttempts})</otherwise></choose>
              FOR UPDATE</script>
            """)
    List<UUID> lockJob(
            @Param("j") Job job,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("maxAttempts") int maxAttempts);

    @Select(
            "SELECT COUNT(*) FROM workspace_publication_manifests WHERE id = #{j.manifestId} AND"
                + " org_id = #{j.orgId} AND user_id = #{j.userId} AND run_id = #{j.runId} AND"
                + " task_id = #{j.taskId} AND attempt_id = #{j.attemptId} AND status = 'READY' AND"
                + " scan_complete")
    int manifestReady(@Param("j") Job job);

    @Update(
            "UPDATE run_attempts SET status = #{status}, error_code = #{code}, completed_at ="
                + " #{now}, updated_at = #{now} WHERE id = #{j.attemptId} AND org_id = #{j.orgId}"
                + " AND run_id = #{j.runId} AND task_id = #{j.taskId} AND status = 'ARCHIVING'")
    int finishAttempt(
            @Param("j") Job job,
            @Param("status") String status,
            @Param("code") String code,
            @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE workspace_archive_jobs SET status = #{status}, token = NULL, lease_until ="
                + " NULL, updated_at = #{now} WHERE id = #{j.id} AND org_id = #{j.orgId} AND status"
                + " IN ('PENDING', 'RUNNING')")
    int finishJob(
            @Param("j") Job job, @Param("status") String status, @Param("now") OffsetDateTime now);
}
