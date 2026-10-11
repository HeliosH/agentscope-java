/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Item;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Job;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceArchiveMapper {
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
                + " a.org_id AND t.status = 'ARCHIVING') AND EXISTS (SELECT 1 FROM agent_runs ar"
                + " WHERE ar.id = a.agent_run_id AND ar.run_id = a.run_id AND ar.task_id ="
                + " a.task_id AND ar.org_id = a.org_id AND ar.status = 'ARCHIVING') FOR UPDATE")
    List<UUID> lockAttempt(@Param("j") Job job);

    @Select(
            "SELECT * FROM workspace_archive_jobs WHERE org_id = #{orgId} AND id = #{id} FOR"
                    + " UPDATE")
    List<Job> lockOwned(@Param("orgId") UUID org, @Param("id") UUID id);

    @Select(
            "SELECT "
                    + FilePublicationMapper.QUALIFIED_COLUMNS
                    + " FROM file_publications p JOIN file_publication_intents i ON"
                    + " i.publication_id = p.id AND i.org_id = p.org_id AND i.user_id = p.user_id"
                    + " WHERE p.org_id = #{s.orgId} AND p.user_id = #{s.userId} AND p.agent_id ="
                    + " #{s.agentId} AND p.session_id = #{s.sessionId} AND p.session_generation ="
                    + " #{s.sessionGeneration} AND p.run_id = #{s.runId} AND i.task_id ="
                    + " #{s.execution.taskId} AND i.agent_run_id = #{s.execution.agentRunId} AND"
                    + " i.attempt_id = #{s.execution.attemptId} AND i.lease_owner ="
                    + " #{s.execution.leaseOwner} AND p.status IN ('STORED', 'PUBLISHED') ORDER BY"
                    + " p.id FOR UPDATE")
    List<Publication> materializedCandidates(@Param("s") Scope scope);

    @Insert(
            """
            INSERT INTO workspace_archive_jobs
              (id, org_id, user_id, agent_id, session_id, session_generation, run_id, task_id, agent_run_id,
               attempt_id, original_owner, manifest_id, body_id, body_bytes, status, deadline_at, next_attempt_at, created_at, result_json, prepared)
            VALUES (#{j.id}, #{j.orgId}, #{j.userId}, #{j.agentId}, #{j.sessionId}, #{j.sessionGeneration}, #{j.runId}, #{j.taskId}, #{j.agentRunId},
              #{j.attemptId}, #{j.originalOwner}, #{j.manifestId}, #{j.bodyId}, #{j.bodyBytes}, 'PENDING', #{j.deadlineAt}, #{j.nextAttemptAt}, #{j.createdAt}, #{result}, #{prepared})
            """)
    int create(
            @Param("j") Job job,
            @Param("result") String result,
            @Param("prepared") boolean prepared);

    @Select(
            "SELECT * FROM workspace_archive_jobs WHERE org_id = #{s.orgId} AND user_id ="
                + " #{s.userId} AND agent_id = #{s.agentId} AND session_id = #{s.sessionId} AND"
                + " session_generation = #{s.sessionGeneration} AND run_id = #{s.runId} AND task_id"
                + " = #{s.execution.taskId} AND agent_run_id = #{s.execution.agentRunId} AND"
                + " attempt_id = #{s.execution.attemptId} AND original_owner ="
                + " #{s.execution.leaseOwner}")
    List<Job> findAttempt(@Param("s") Scope scope);

    @Select(
            "SELECT EXISTS (SELECT 1 FROM workspace_archive_jobs WHERE id = #{j.id} AND org_id ="
                    + " #{j.orgId} AND user_id = #{j.userId} AND prepared)")
    boolean isPrepared(@Param("j") Job job);

    @Update(
            """
            UPDATE workspace_archive_items e SET publication_id = (SELECT me.publication_id FROM workspace_manifest_entries me
              JOIN file_publications p ON p.id = me.publication_id AND p.org_id = me.org_id AND p.user_id = me.user_id
              WHERE me.manifest_id = #{j.manifestId} AND me.org_id = e.org_id AND me.user_id = e.user_id
                AND me.logical_path = e.logical_path AND p.logical_path = me.logical_path AND p.sha256 = me.sha256 AND p.size_bytes = me.size_bytes
                AND p.status = 'PUBLISHED' AND p.run_id = #{j.runId} AND p.agent_id = #{j.agentId} AND p.session_id = #{j.sessionId} AND p.session_generation = #{j.sessionGeneration})
             WHERE e.job_id = #{j.id} AND e.org_id = #{j.orgId} AND e.user_id = #{j.userId} AND e.publication_id IS NULL
               AND EXISTS (SELECT 1 FROM workspace_archive_jobs j WHERE j.id = e.job_id AND j.org_id = e.org_id AND j.user_id = e.user_id
                    AND j.manifest_id = #{j.manifestId} AND j.status = 'RUNNING' AND j.token = #{j.token} AND j.lease_until > #{now} AND j.deadline_at > #{now})
            """)
    int bindPublishedItems(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT a.id FROM run_attempts a WHERE a.id = #{j.attemptId} AND a.org_id = #{j.orgId}
              AND a.run_id = #{j.runId} AND a.task_id = #{j.taskId} AND a.agent_run_id = #{j.agentRunId}
              AND a.status = 'RUNNING' AND COALESCE(a.lease_owner, a.idempotency_key) = #{j.originalOwner}
              AND EXISTS (SELECT 1 FROM task_nodes t WHERE t.id = a.task_id AND t.org_id = a.org_id AND t.run_id = a.run_id AND t.status = 'RUNNING')
              AND EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.id = a.agent_run_id AND ar.org_id = a.org_id AND ar.task_id = a.task_id AND ar.run_id = a.run_id AND ar.status = 'RUNNING')
              FOR UPDATE
            """)
    List<UUID> lockPreparedAttempt(@Param("j") Job job);

    @Update(
            """
            UPDATE run_attempts SET status = 'ARCHIVING', lease_expires_at = NULL, lease_owner = #{j.originalOwner}, updated_at = #{now}
             WHERE id = #{j.attemptId} AND org_id = #{j.orgId} AND run_id = #{j.runId} AND task_id = #{j.taskId} AND agent_run_id = #{j.agentRunId}
               AND status = 'RUNNING' AND COALESCE(lease_owner, idempotency_key) = #{j.originalOwner}
               AND EXISTS (SELECT 1 FROM workspace_archive_jobs j WHERE j.id = #{j.id} AND j.prepared AND j.status = 'PENDING' AND j.attempt_id = run_attempts.id AND j.org_id = run_attempts.org_id)
            """)
    int activatePreparedAttempt(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE workspace_archive_jobs SET prepared = FALSE, next_attempt_at = #{now},"
                + " updated_at = #{now} WHERE id = #{j.id} AND org_id = #{j.orgId} AND user_id ="
                + " #{j.userId} AND prepared AND status = 'PENDING'")
    int activatePrepared(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE workspace_archive_jobs j SET prepared = FALSE, updated_at = #{now}, status = CASE
              WHEN EXISTS (SELECT 1 FROM run_attempts a WHERE a.id = j.attempt_id AND a.org_id = j.org_id AND a.run_id = j.run_id AND a.task_id = j.task_id AND a.agent_run_id = j.agent_run_id AND a.status = 'SUCCEEDED')
               AND EXISTS (SELECT 1 FROM workspace_publication_manifests m WHERE m.id = j.manifest_id AND m.org_id = j.org_id AND m.status = 'READY')
                THEN 'COMPLETED' ELSE 'CANCELLED' END
             WHERE j.id = #{j.id} AND j.org_id = #{j.orgId} AND j.user_id = #{j.userId} AND j.prepared AND j.status = 'PENDING'
               AND NOT EXISTS (SELECT 1 FROM run_attempts a JOIN assistant_runs r ON r.id = a.run_id AND r.org_id = a.org_id
                  JOIN chat_sessions s ON s.id = r.session_id AND s.org_id = r.org_id AND s.user_id = r.user_id AND s.agent_id = r.agent_id
                 WHERE a.id = j.attempt_id AND a.org_id = j.org_id AND a.run_id = j.run_id AND a.task_id = j.task_id AND a.agent_run_id = j.agent_run_id
                   AND COALESCE(a.lease_owner, a.idempotency_key) = j.original_owner AND a.status = 'RUNNING'
                   AND r.user_id = j.user_id AND r.agent_id = j.agent_id AND r.session_id = j.session_id AND r.session_generation = j.session_generation
                   AND r.status = 'RUNNING' AND NOT r.cancel_requested AND s.execution_generation = j.session_generation)
            """)
    int settlePrepared(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Select(
            "SELECT result_json FROM workspace_archive_jobs WHERE id = #{id} AND org_id = #{orgId}"
                + " AND user_id = #{userId} AND manifest_id = #{manifestId} AND body_id IS NULL")
    List<String> inlineResult(Job job);

    @Select(
            "<script>SELECT COALESCE(SUM(body_bytes), 0) FROM workspace_archive_jobs WHERE org_id ="
                + " #{orgId} AND result_json IS NOT NULL <if test='userId != null'>AND user_id ="
                + " #{userId}</if></script>")
    long inlineBytes(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Insert(
            """
            <script>INSERT INTO workspace_archive_items (job_id, org_id, user_id, logical_path, publication_id)
              VALUES <foreach collection="items" item="i" separator=",">
                (#{j.id}, #{j.orgId}, #{j.userId}, #{i.logicalPath}, #{i.publicationId})
              </foreach></script>
            """)
    int createItems(@Param("j") Job job, @Param("items") List<Item> items);

    @Update(
            "UPDATE run_attempts SET status = 'ARCHIVING', lease_expires_at = NULL, updated_at ="
                + " #{now} WHERE id = #{j.attemptId} AND org_id = #{j.orgId} AND run_id ="
                + " #{j.runId} AND task_id = #{j.taskId} AND agent_run_id = #{j.agentRunId} AND"
                + " status = 'RUNNING' AND ((lease_owner = #{j.originalOwner} AND lease_expires_at"
                + " > #{now}) OR (lease_owner IS NULL AND lease_expires_at IS NULL AND"
                + " idempotency_key = #{j.originalOwner}))")
    int transitionAttempt(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE run_attempts SET lease_owner = #{j.originalOwner} WHERE id = #{j.attemptId} AND"
                    + " org_id = #{j.orgId} AND status = 'ARCHIVING' AND lease_owner IS NULL")
    int bindLegacyOwner(@Param("j") Job job);

    @Update(
            "UPDATE task_nodes SET status = 'ARCHIVING', next_attempt_at = NULL, next_recovery_at ="
                + " NULL, updated_at = #{now} WHERE id = #{j.taskId} AND org_id = #{j.orgId} AND"
                + " run_id = #{j.runId} AND status = 'RUNNING'")
    int transitionTask(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE agent_runs SET status = 'ARCHIVING', updated_at = #{now} WHERE id ="
                + " #{j.agentRunId} AND org_id = #{j.orgId} AND run_id = #{j.runId} AND task_id ="
                + " #{j.taskId} AND status = 'RUNNING'")
    int transitionAgent(@Param("j") Job job, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE workspace_archive_jobs SET status = 'RUNNING', token = #{token}, lease_until ="
                + " #{until}, attempts = attempts + 1, updated_at = #{now} WHERE id = #{j.id} AND"
                + " org_id = #{j.orgId} AND deadline_at > #{now} AND attempts < #{maxAttempts} AND"
                + " NOT prepared AND next_attempt_at <= #{now} AND (status = 'PENDING' OR (status ="
                + " 'RUNNING' AND lease_until <= #{now}))")
    int claim(
            @Param("j") Job job,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until,
            @Param("maxAttempts") int maxAttempts);

    @Select(
            "SELECT p.id, p.org_id, p.user_id, p.agent_id, p.session_id, p.run_id,"
                + " p.session_generation, p.logical_path, p.object_key, p.backend, p.sha256,"
                + " p.size_bytes, p.reserved_bytes, p.owns_object, p.status, p.lease_until,"
                + " p.eligible_at, p.claim_token, p.attempts, p.version_id FROM"
                + " workspace_archive_items e JOIN file_publications p ON p.id = e.publication_id"
                + " AND p.org_id = e.org_id AND p.user_id = e.user_id AND p.logical_path ="
                + " e.logical_path WHERE e.job_id = #{j.id} AND e.org_id = #{j.orgId} AND e.user_id"
                + " = #{j.userId} ORDER BY e.logical_path")
    List<Publication> publications(@Param("j") Job job);

    @Select(
            "SELECT logical_path, publication_id FROM workspace_archive_items WHERE job_id = #{id}"
                    + " AND org_id = #{orgId} AND user_id = #{userId} ORDER BY logical_path")
    List<Item> items(Job job);

    @Update(
            "UPDATE workspace_archive_jobs SET status = 'PENDING', token = NULL, lease_until ="
                + " NULL, next_attempt_at = #{retryAt}, updated_at = #{now} WHERE id = #{j.id} AND"
                + " org_id = #{j.orgId} AND status = 'RUNNING' AND token = #{token} AND lease_until"
                + " > #{now}")
    int defer(
            @Param("j") Job job,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("retryAt") OffsetDateTime retryAt);

    @Update(
            "UPDATE workspace_archive_jobs SET prepared = FALSE, status = 'CANCELLED', token ="
                + " NULL, lease_until = NULL, updated_at = #{now} WHERE id = #{j.id} AND org_id ="
                + " #{j.orgId} AND user_id = #{j.userId} AND agent_id = #{j.agentId} AND session_id"
                + " = #{j.sessionId} AND session_generation = #{j.sessionGeneration} AND run_id ="
                + " #{j.runId} AND task_id = #{j.taskId} AND attempt_id = #{j.attemptId} AND"
                + " agent_run_id = #{j.agentRunId} AND original_owner = #{j.originalOwner} AND"
                + " manifest_id = #{j.manifestId} AND body_id IS NOT DISTINCT FROM #{j.bodyId} AND"
                + " status IN ('PENDING', 'RUNNING')")
    int revoke(@Param("j") Job job, @Param("now") OffsetDateTime now);
}
