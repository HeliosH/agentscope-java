/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Entry;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceManifestMapper {
    @Select(
            """
            SELECT id FROM chat_sessions WHERE id = #{s.sessionId} AND org_id = #{s.orgId}
              AND user_id = #{s.userId} AND agent_id = #{s.agentId} AND execution_generation = #{s.sessionGeneration}
              FOR UPDATE
            """)
    List<UUID> lockSession(@Param("s") Scope scope);

    @Select(
            """
            SELECT id FROM assistant_runs WHERE id = #{s.runId} AND org_id = #{s.orgId}
              AND user_id = #{s.userId} AND agent_id = #{s.agentId} AND session_id = #{s.sessionId}
              AND session_generation = #{s.sessionGeneration} AND NOT cancel_requested
              AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') FOR UPDATE
            """)
    List<UUID> lockRun(@Param("s") Scope scope);

    @Select(
            """
            SELECT a.id FROM run_attempts a WHERE a.id = #{s.execution.attemptId} AND a.org_id = #{s.orgId}
              AND a.run_id = #{s.runId} AND a.task_id = #{s.execution.taskId} AND a.agent_run_id = #{s.execution.agentRunId}
              AND a.status = 'RUNNING'
              AND ((a.lease_owner = #{s.execution.leaseOwner} AND a.lease_expires_at > #{now})
                OR (a.lease_owner IS NULL AND a.lease_expires_at IS NULL AND a.idempotency_key = #{s.execution.leaseOwner}))
              AND EXISTS (SELECT 1 FROM task_nodes t WHERE t.id = a.task_id AND t.org_id = a.org_id AND t.run_id = a.run_id AND t.status = 'RUNNING')
              AND EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.id = a.agent_run_id AND ar.org_id = a.org_id AND ar.run_id = a.run_id AND ar.task_id = a.task_id AND ar.status = 'RUNNING')
              FOR UPDATE
            """)
    List<UUID> lockAttempt(@Param("s") Scope scope, @Param("now") OffsetDateTime now);

    @Select(
            "SELECT COALESCE(MAX(manifest_seq), 0) + 1 FROM workspace_publication_manifests WHERE"
                + " org_id = #{s.orgId} AND run_id = #{s.runId} AND task_id = #{s.execution.taskId}"
                + " AND attempt_id = #{s.execution.attemptId}")
    long nextSequence(@Param("s") Scope scope);

    @Insert(
            """
            INSERT INTO workspace_publication_manifests
              (id, org_id, user_id, agent_id, session_id, session_generation, run_id, task_id, agent_run_id,
               attempt_id, lease_owner, manifest_seq, plan_hash, file_count, total_bytes, scanned_files, status, scan_complete, created_at)
            VALUES (#{id}, #{scope.orgId}, #{scope.userId}, #{scope.agentId}, #{scope.sessionId}, #{scope.sessionGeneration},
              #{scope.runId}, #{scope.execution.taskId}, #{scope.execution.agentRunId}, #{scope.execution.attemptId},
              #{scope.execution.leaseOwner}, #{sequence}, #{planHash}, #{fileCount}, #{totalBytes}, #{scannedFiles}, #{status}, #{scanComplete}, #{createdAt})
            """)
    int insert(Manifest manifest);

    @Insert(
            """
            <script>INSERT INTO workspace_manifest_entries
              (manifest_id, org_id, user_id, source_path, logical_path, size_bytes, sha256, unchanged)
            VALUES <foreach collection="entries" item="e" separator=",">
              (#{m.id}, #{m.scope.orgId}, #{m.scope.userId}, #{e.sourcePath}, #{e.logicalPath}, #{e.sizeBytes}, #{e.sha256}, #{e.unchanged})
            </foreach></script>
            """)
    int insertEntries(@Param("m") Manifest manifest, @Param("entries") List<Entry> entries);

    @Select(
            """
            SELECT m.* FROM workspace_publication_manifests m WHERE m.id = #{id} AND m.org_id = #{orgId}
              AND m.run_id = #{runId} AND m.task_id = #{taskId} AND m.attempt_id = #{attemptId}
              AND NOT EXISTS (SELECT 1 FROM workspace_publication_manifests newer WHERE newer.org_id = m.org_id
                   AND newer.run_id = m.run_id AND newer.task_id = m.task_id AND newer.attempt_id = m.attempt_id AND newer.manifest_seq > m.manifest_seq)
              FOR UPDATE
            """)
    List<WorkspaceManifestData> lockCurrent(
            @Param("orgId") UUID org,
            @Param("runId") UUID run,
            @Param("taskId") UUID task,
            @Param("attemptId") UUID attempt,
            @Param("id") UUID id);

    @Select(
            """
            SELECT r.org_id, r.user_id, r.agent_id, r.session_id, r.session_generation, r.id AS run_id,
                   a.task_id, a.agent_run_id, a.id AS attempt_id, COALESCE(a.lease_owner, a.idempotency_key) AS lease_owner
              FROM run_attempts a
              JOIN assistant_runs r ON r.id = a.run_id AND r.org_id = a.org_id
             WHERE a.id = #{attemptId} AND a.org_id = #{orgId} AND r.user_id = #{userId}
            """)
    List<WorkspaceRestoreScopeData> restoreScope(
            @Param("orgId") UUID org, @Param("userId") UUID user, @Param("attemptId") UUID attempt);

    @Select(
            """
            SELECT m.* FROM workspace_publication_manifests m
             WHERE m.id = #{id} AND m.org_id = #{orgId} AND m.user_id = #{userId}
               AND m.run_id = #{runId} AND m.task_id = #{taskId} AND m.attempt_id = #{attemptId}
            """)
    List<WorkspaceManifestData> findCheckpoint(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("runId") UUID run,
            @Param("taskId") UUID task,
            @Param("attemptId") UUID attempt,
            @Param("id") UUID id);

    @Select(
            """
            SELECT EXISTS (SELECT 1 FROM workspace_publication_manifests
             WHERE org_id = #{orgId} AND user_id = #{userId} AND run_id = #{runId}
               AND task_id = #{taskId} AND attempt_id = #{attemptId})
            """)
    boolean hasAttemptManifests(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("runId") UUID run,
            @Param("taskId") UUID task,
            @Param("attemptId") UUID attempt);

    @Select(
            "SELECT source_path, logical_path, size_bytes, sha256, unchanged, publication_id FROM"
                    + " workspace_manifest_entries WHERE org_id = #{orgId} AND manifest_id = #{id}"
                    + " ORDER BY logical_path")
    List<Entry> entries(@Param("orgId") UUID org, @Param("id") UUID id);

    @Update(
            """
            UPDATE workspace_manifest_entries SET publication_id = #{p.id}
             WHERE manifest_id = #{id} AND org_id = #{p.orgId} AND user_id = #{p.userId}
               AND logical_path = #{p.logicalPath} AND sha256 = #{p.sha256} AND size_bytes = #{p.sizeBytes}
               AND EXISTS (SELECT 1 FROM workspace_publication_manifests m WHERE m.id = workspace_manifest_entries.manifest_id
                   AND m.org_id = #{p.orgId} AND m.user_id = #{p.userId} AND m.agent_id = #{p.agentId}
                   AND m.session_id = #{p.sessionId} AND m.session_generation = #{p.sessionGeneration} AND m.run_id = #{p.runId}
                   AND m.task_id = #{i.taskId} AND m.agent_run_id = #{i.agentRunId} AND m.attempt_id = #{i.attemptId}
                   AND m.lease_owner = #{i.leaseOwner} AND m.status IN ('DISCOVERED', 'SCANNED')
                   AND NOT EXISTS (SELECT 1 FROM workspace_publication_manifests n WHERE n.org_id = m.org_id
                        AND n.run_id = m.run_id AND n.task_id = m.task_id AND n.attempt_id = m.attempt_id AND n.manifest_seq > m.manifest_seq))
               AND EXISTS (SELECT 1 FROM file_publications p WHERE p.id = #{p.id} AND p.org_id = #{p.orgId}
                   AND p.user_id = #{p.userId} AND p.status = 'PUBLISHED' AND p.artifact_id IS NOT NULL AND p.receipt_event_id IS NOT NULL
                   AND (workspace_manifest_entries.publication_id IS NULL OR EXISTS (
                        SELECT 1 FROM file_publications old WHERE old.id = workspace_manifest_entries.publication_id
                         AND old.org_id = p.org_id AND old.user_id = p.user_id AND old.version_id = p.version_id)))
            """)
    int recordPublication(@Param("id") UUID id, @Param("p") Publication p, @Param("i") Intent i);

    @Update(
            """
            UPDATE workspace_publication_manifests SET status = 'SCANNED', scan_complete = TRUE,
                   projected_files = #{projected}, unchanged_files = #{unchanged}, updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id} AND org_id = #{s.orgId} AND user_id = #{s.userId} AND run_id = #{s.runId}
               AND task_id = #{s.execution.taskId} AND attempt_id = #{s.execution.attemptId} AND lease_owner = #{s.execution.leaseOwner}
               AND status IN ('DISCOVERED', 'SCANNED') AND file_count = #{projected} + #{unchanged}
            """)
    int scanCompleted(
            @Param("s") Scope scope,
            @Param("id") UUID id,
            @Param("projected") int projected,
            @Param("unchanged") int unchanged);

    @Update(
            "UPDATE workspace_publication_manifests SET status = 'READY', updated_at ="
                    + " CURRENT_TIMESTAMP WHERE id = #{id} AND org_id = #{orgId} AND status IN"
                    + " ('SCANNED', 'READY') AND scan_complete")
    int ready(@Param("orgId") UUID org, @Param("id") UUID id);

    @Select(
            """
            SELECT a.id AS artifact_id, o.id AS event_id, a.file_id, a.file_version_id AS version_id,
                   p.logical_path, p.object_key, p.backend, p.sha256, p.size_bytes
              FROM workspace_manifest_entries e
              JOIN file_publications p ON p.id = e.publication_id AND p.org_id = e.org_id AND p.user_id = e.user_id
                   AND p.logical_path = e.logical_path AND p.sha256 = e.sha256 AND p.size_bytes = e.size_bytes AND p.status = 'PUBLISHED'
              JOIN file_publication_intents i ON i.publication_id = p.id AND i.org_id = p.org_id AND i.user_id = p.user_id
              JOIN run_artifacts a ON a.id = p.artifact_id AND a.org_id = p.org_id AND a.run_id = p.run_id
                   AND a.task_id = i.task_id AND a.attempt_id = i.attempt_id AND a.file_version_id = p.version_id AND a.logical_path = p.logical_path
                   AND a.artifact_type = 'WORKSPACE_FILE'
              JOIN file_versions v ON v.id = p.version_id AND v.org_id = p.org_id AND v.user_id = p.user_id AND v.file_id = a.file_id
                   AND v.object_key = p.object_key AND v.storage_backend = p.backend AND v.sha256 = p.sha256 AND v.size_bytes = p.size_bytes
              JOIN files f ON f.id = v.file_id AND f.org_id = p.org_id AND f.user_id = p.user_id
              JOIN orchestration_outbox o ON o.id = p.receipt_event_id AND o.org_id = p.org_id
                   AND o.aggregate_id = a.id AND o.aggregate_type = 'run_artifact' AND o.event_type = 'workspace_file_published'
             WHERE e.manifest_id = #{id} AND p.org_id = #{s.orgId} AND p.user_id = #{s.userId}
               AND p.run_id = #{s.runId} AND p.agent_id = #{s.agentId} AND p.session_id = #{s.sessionId} AND p.session_generation = #{s.sessionGeneration}
               AND i.task_id = #{s.execution.taskId} AND i.agent_run_id = #{s.execution.agentRunId}
               AND i.attempt_id = #{s.execution.attemptId} AND i.lease_owner = #{s.execution.leaseOwner}
            """)
    List<ArtifactReceipt> receipts(@Param("s") Scope scope, @Param("id") UUID id);
}
