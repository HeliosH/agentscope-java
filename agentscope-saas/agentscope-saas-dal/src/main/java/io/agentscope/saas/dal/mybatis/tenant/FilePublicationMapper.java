/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Recovery;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository.Authority;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface FilePublicationMapper {
    String COLUMNS =
            "id, org_id, user_id, agent_id, session_id, run_id, session_generation, logical_path,"
                + " object_key, backend, sha256, size_bytes, reserved_bytes, owns_object, status,"
                + " lease_until, eligible_at, claim_token, attempts, version_id";
    String QUALIFIED_COLUMNS =
            "p.id, p.org_id, p.user_id, p.agent_id, p.session_id, p.run_id, p.session_generation,"
                    + " p.logical_path, p.object_key, p.backend, p.sha256, p.size_bytes,"
                    + " p.reserved_bytes, p.owns_object, p.status, p.lease_until, p.eligible_at,"
                    + " p.claim_token, p.attempts, p.version_id";

    @Insert(
            """
            INSERT INTO file_publications
              (id, org_id, user_id, agent_id, session_id, run_id, session_generation, logical_path,
               object_key, backend, sha256, size_bytes, reserved_bytes, owns_object, status, lease_until, eligible_at)
            VALUES (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{runId}, #{sessionGeneration}, #{logicalPath},
                    #{objectKey}, #{backend}, #{sha256}, #{sizeBytes}, #{reservedBytes}, #{ownsObject}, 'STAGED', #{leaseUntil}, #{eligibleAt})
            """)
    int stage(Publication publication);

    @Insert(
            """
            INSERT INTO file_publication_intents
              (publication_id, org_id, user_id, base_file_id, base_version_id, base_status, content_type,
               source, metadata_json, task_id, agent_run_id, attempt_id, lease_owner, raw_write_required)
            VALUES (#{i.publicationId}, #{p.orgId}, #{p.userId}, #{i.baseFileId}, #{i.baseVersionId}, #{i.baseStatus},
                    #{i.contentType}, #{i.source}, #{i.metadataJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                    #{i.taskId}, #{i.agentRunId}, #{i.attemptId}, #{i.leaseOwner}, #{i.rawWriteRequired})
            """)
    int saveIntent(@Param("p") Publication p, @Param("i") Intent i);

    @Update(
            """
            UPDATE file_publications SET artifact_id = #{artifactId}, receipt_event_id = #{eventId}
             WHERE id = #{p.id} AND org_id = #{p.orgId} AND user_id = #{p.userId}
               AND run_id = #{p.runId} AND status = 'PUBLISHED'
               AND artifact_id IS NULL AND receipt_event_id IS NULL
               AND EXISTS (
                 SELECT 1 FROM run_artifacts a
                  JOIN file_publication_intents i ON i.publication_id = file_publications.id
                       AND i.org_id = a.org_id AND i.user_id = file_publications.user_id
                  JOIN orchestration_outbox o ON o.id = #{eventId} AND o.org_id = a.org_id
                       AND o.aggregate_id = a.id AND o.aggregate_type = 'run_artifact'
                       AND o.event_type = 'workspace_file_published'
                  WHERE a.id = #{artifactId} AND a.org_id = file_publications.org_id
                    AND a.run_id = file_publications.run_id AND a.task_id = i.task_id
                    AND a.attempt_id = i.attempt_id AND a.file_version_id = file_publications.version_id
                    AND a.logical_path = file_publications.logical_path AND a.artifact_type = 'WORKSPACE_FILE')
            """)
    int linkArtifactReceipt(
            @Param("p") Publication p,
            @Param("artifactId") UUID artifactId,
            @Param("eventId") UUID eventId);

    @Select(
            """
            SELECT DISTINCT a.id AS artifact_id, o.id AS event_id, a.file_id, a.file_version_id AS version_id,
                   p.logical_path, p.object_key, p.backend, p.sha256, p.size_bytes
              FROM file_publications p
              JOIN file_publication_intents i ON i.publication_id = p.id AND i.org_id = p.org_id AND i.user_id = p.user_id
              JOIN run_artifacts a ON a.id = p.artifact_id AND a.org_id = p.org_id
                   AND a.run_id = p.run_id AND a.task_id = i.task_id AND a.attempt_id = i.attempt_id
                   AND a.file_version_id = p.version_id AND a.logical_path = p.logical_path AND a.artifact_type = 'WORKSPACE_FILE'
              JOIN file_versions v ON v.id = p.version_id AND v.org_id = p.org_id AND v.user_id = p.user_id AND v.file_id = a.file_id
                   AND v.object_key = p.object_key AND v.storage_backend = p.backend
                   AND v.sha256 = p.sha256 AND v.size_bytes = p.size_bytes
              JOIN files f ON f.id = v.file_id AND f.org_id = p.org_id AND f.user_id = p.user_id
              JOIN orchestration_outbox o ON o.id = p.receipt_event_id AND o.org_id = p.org_id
                   AND o.aggregate_id = a.id AND o.aggregate_type = 'run_artifact' AND o.event_type = 'workspace_file_published'
              JOIN assistant_runs r ON r.id = p.run_id AND r.org_id = p.org_id AND r.user_id = p.user_id
                   AND r.agent_id = p.agent_id AND r.session_id = p.session_id AND r.session_generation = p.session_generation
              JOIN run_attempts b ON b.id = i.attempt_id AND b.org_id = p.org_id AND b.run_id = p.run_id
                   AND b.task_id = i.task_id AND b.agent_run_id = i.agent_run_id
             WHERE p.org_id = #{orgId} AND p.run_id = #{runId} AND i.task_id = #{taskId}
               AND i.attempt_id = #{attemptId} AND p.status = 'PUBLISHED'
            """)
    List<ArtifactReceipt> artifactReceipts(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("attemptId") UUID attemptId);

    @Update(
            """
            UPDATE file_publications SET recoverable = #{enabled}, recovery_due_at = lease_until,
                   recovery_deadline_at = #{deadline}
             WHERE id = #{p.id} AND org_id = #{p.orgId} AND user_id = #{p.userId} AND status = 'STAGED'
            """)
    int enableRecovery(
            @Param("p") Publication p,
            @Param("enabled") boolean enabled,
            @Param("deadline") OffsetDateTime deadline);

    @Select(
            """
            SELECT publication_id, base_file_id, base_version_id, base_status, content_type, source,
                   metadata_json, task_id, agent_run_id, attempt_id, lease_owner, raw_write_required
              FROM file_publication_intents WHERE publication_id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
            """)
    List<Intent> intent(@Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Select(
            "SELECT recovery_token, recovery_attempts, recovery_deadline_at FROM file_publications"
                    + " WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}")
    List<Recovery> recovery(
            @Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Select(
            """
            SELECT r.id FROM assistant_runs r WHERE r.id = #{p.runId} AND r.org_id = #{p.orgId}
              AND r.user_id = #{p.userId} AND r.agent_id = #{p.agentId}
              AND r.session_id = #{p.sessionId} AND r.session_generation = #{p.sessionGeneration}
              AND r.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND NOT r.cancel_requested FOR UPDATE
            """)
    List<UUID> lockExecutionRun(@Param("p") Publication p);

    @Select(
            """
            SELECT a.id FROM run_attempts a WHERE a.id = #{i.attemptId} AND a.org_id = #{p.orgId}
              AND a.run_id = #{p.runId} AND a.task_id = #{i.taskId} AND a.agent_run_id = #{i.agentRunId}
              AND a.status = 'RUNNING'
              AND EXISTS (SELECT 1 FROM task_nodes t WHERE t.id = a.task_id AND t.run_id = a.run_id AND t.org_id = a.org_id AND t.status = 'RUNNING')
              AND EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.id = a.agent_run_id AND ar.task_id = a.task_id AND ar.run_id = a.run_id AND ar.org_id = a.org_id AND ar.status = 'RUNNING')
              AND ((a.lease_owner = #{i.leaseOwner} AND a.lease_expires_at >= #{now})
                   OR (a.lease_owner IS NULL AND a.lease_expires_at IS NULL AND a.idempotency_key = #{i.leaseOwner}))
              FOR UPDATE
            """)
    List<UUID> lockExecutionAttempt(
            @Param("p") Publication p, @Param("i") Intent i, @Param("now") OffsetDateTime now);

    @Select(
            "SELECT a.id FROM run_attempts a WHERE a.id = #{i.attemptId} AND a.org_id = #{p.orgId}"
                + " AND a.run_id = #{p.runId} AND a.task_id = #{i.taskId} AND a.agent_run_id ="
                + " #{i.agentRunId} AND a.status = 'ARCHIVING' AND a.lease_owner = #{i.leaseOwner}"
                + " AND a.lease_expires_at IS NULL AND EXISTS (SELECT 1 FROM task_nodes t WHERE"
                + " t.id = a.task_id AND t.org_id = a.org_id AND t.run_id = a.run_id AND t.status ="
                + " 'ARCHIVING') AND EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.id ="
                + " a.agent_run_id AND ar.org_id = a.org_id AND ar.run_id = a.run_id AND ar.task_id"
                + " = a.task_id AND ar.status = 'ARCHIVING') FOR UPDATE")
    List<UUID> lockArchiveAttempt(@Param("p") Publication p, @Param("i") Intent i);

    @Select(
            """
            SELECT p.id FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id AND j.org_id = e.org_id AND j.user_id = e.user_id
              JOIN file_publications p ON p.id = e.publication_id AND p.org_id = e.org_id AND p.user_id = e.user_id
              WHERE e.org_id = #{orgId} AND e.user_id = #{userId} AND e.logical_path = #{path}
                AND j.id = #{a.jobId} AND j.token = #{a.token} AND j.status = 'RUNNING' AND j.lease_until > #{now} AND j.deadline_at > #{now}
                AND (p.status IN ('ABORTED', 'DELETED') OR (p.status = 'STAGED' AND p.lease_until <= #{now}))
            """)
    List<UUID> archiveReplacement(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("path") String path,
            @Param("a") Authority authority,
            @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT j.id FROM workspace_archive_jobs j
             WHERE j.id = #{a.jobId} AND j.org_id = #{p.orgId} AND j.user_id = #{p.userId}
               AND j.agent_id = #{p.agentId} AND j.session_id = #{p.sessionId} AND j.session_generation = #{p.sessionGeneration}
               AND j.run_id = #{p.runId} AND j.task_id = #{i.taskId} AND j.agent_run_id = #{i.agentRunId}
               AND j.attempt_id = #{i.attemptId} AND j.original_owner = #{i.leaseOwner}
               AND j.status = 'RUNNING' AND j.token = #{a.token} AND j.lease_until > #{now} AND j.deadline_at > #{now}
               AND EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_manifest_entries me
                    ON me.manifest_id = j.manifest_id AND me.org_id = e.org_id AND me.user_id = e.user_id AND me.logical_path = e.logical_path
                    WHERE e.job_id = j.id AND e.org_id = j.org_id AND e.user_id = j.user_id AND e.publication_id = #{p.id}
                      AND e.logical_path = #{p.logicalPath} AND me.sha256 = #{p.sha256} AND me.size_bytes = #{p.sizeBytes})
               AND EXISTS (SELECT 1 FROM workspace_publication_manifests m WHERE m.id = j.manifest_id AND m.org_id = j.org_id
                    AND m.user_id = j.user_id AND m.attempt_id = j.attempt_id AND m.task_id = j.task_id AND m.run_id = j.run_id
                    AND m.scan_complete AND m.status IN ('SCANNED', 'READY')
                    AND NOT EXISTS (SELECT 1 FROM workspace_publication_manifests n WHERE n.org_id = m.org_id AND n.run_id = m.run_id
                         AND n.task_id = m.task_id AND n.attempt_id = m.attempt_id AND n.manifest_seq > m.manifest_seq))
             FOR UPDATE
            """)
    List<UUID> lockArchiveAuthority(
            @Param("p") Publication p,
            @Param("i") Intent i,
            @Param("a") Authority authority,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE workspace_archive_items SET publication_id = #{p.id}
              WHERE job_id = #{a.jobId} AND org_id = #{p.orgId} AND user_id = #{p.userId}
                AND logical_path = #{p.logicalPath}
                AND (publication_id IS NULL OR EXISTS (SELECT 1 FROM file_publications old
                     WHERE old.id = workspace_archive_items.publication_id AND old.org_id = #{p.orgId}
                       AND old.user_id = #{p.userId} AND (old.status IN ('ABORTED', 'DELETED')
                            OR (old.status = 'STAGED' AND old.lease_until <= #{now}))))
                AND EXISTS (SELECT 1 FROM workspace_archive_jobs j
                     JOIN workspace_publication_manifests m ON m.id = j.manifest_id AND m.org_id = j.org_id AND m.user_id = j.user_id
                     JOIN workspace_manifest_entries me ON me.manifest_id = m.id AND me.org_id = m.org_id AND me.user_id = m.user_id
                     JOIN workspace_sources s ON s.manifest_id = m.id AND s.org_id = m.org_id AND s.user_id = m.user_id
                     JOIN workspace_source_files b ON b.source_id = s.id AND b.org_id = s.org_id AND b.user_id = s.user_id AND b.logical_path = me.logical_path
                     WHERE j.id = workspace_archive_items.job_id AND j.org_id = #{p.orgId} AND j.user_id = #{p.userId}
                       AND j.agent_id = #{p.agentId} AND j.session_id = #{p.sessionId} AND j.session_generation = #{p.sessionGeneration}
                       AND j.run_id = #{p.runId} AND j.task_id = #{i.taskId} AND j.agent_run_id = #{i.agentRunId}
                       AND j.attempt_id = #{i.attemptId} AND j.original_owner = #{i.leaseOwner}
                       AND j.status = 'RUNNING' AND j.token = #{a.token} AND j.lease_until > #{now} AND j.deadline_at > #{now}
                       AND s.deadline_at > #{now} AND (s.status = 'RETAINED' OR (s.status = 'STAGING' AND EXISTS
                         (SELECT 1 FROM workspace_provider_retentions pr WHERE pr.source_id = s.id AND pr.org_id = s.org_id
                            AND pr.user_id = s.user_id AND pr.manifest_id = s.manifest_id AND pr.status = 'RETAINED' AND pr.deadline_at > #{now})))
                       AND me.logical_path = #{p.logicalPath} AND me.size_bytes = #{p.sizeBytes} AND me.sha256 = #{p.sha256}
                       AND b.base_file_id IS NOT DISTINCT FROM #{i.baseFileId}
                       AND b.base_version_id IS NOT DISTINCT FROM #{i.baseVersionId}
                       AND b.base_status IS NOT DISTINCT FROM #{i.baseStatus}
                       AND m.scan_complete AND m.status = 'SCANNED'
                       AND NOT EXISTS (SELECT 1 FROM workspace_publication_manifests n WHERE n.org_id = m.org_id AND n.run_id = m.run_id
                            AND n.task_id = m.task_id AND n.attempt_id = m.attempt_id AND n.manifest_seq > m.manifest_seq))
            """)
    int bindArchivePublication(
            @Param("p") Publication p,
            @Param("i") Intent i,
            @Param("a") Authority authority,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE file_publications SET status = 'ABORTED', eligible_at = #{now}, lease_until = #{now}
              WHERE id = #{p.id} AND org_id = #{p.orgId} AND user_id = #{p.userId} AND status = 'STAGED'
                AND EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j
                    ON j.id = e.job_id AND j.org_id = e.org_id AND j.user_id = e.user_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = #{p.orgId} AND e.user_id = #{p.userId}
                      AND j.id = #{a.jobId} AND j.token = #{a.token} AND j.status = 'RUNNING'
                      AND j.lease_until > #{now} AND j.deadline_at > #{now})
            """)
    int abortArchive(
            @Param("p") Publication p,
            @Param("a") Authority authority,
            @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE file_publications SET recovery_token = #{a.token}, recovery_attempts ="
                + " recovery_attempts + 1, lease_until = #{until}, eligible_at = #{until},"
                + " updated_at = #{now} WHERE id = #{p.id} AND org_id = #{p.orgId} AND user_id ="
                + " #{p.userId} AND status = 'STORED' AND recoverable AND (recovery_token IS NULL"
                + " OR lease_until <= #{now}) AND recovery_deadline_at > #{now}")
    int reclaimArchive(
            @Param("p") Publication p,
            @Param("a") Authority authority,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until);

    @Update(
            """
            UPDATE file_publications SET status = 'STORED', recovery_token = #{token},
              recovery_attempts = recovery_attempts + 1, lease_until = #{until}, eligible_at = #{until}, updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND recoverable AND status IN ('STAGED', 'STORED') AND lease_until <= #{now}
               AND recovery_due_at <= #{now} AND recovery_deadline_at > #{now} AND recovery_attempts < #{maxAttempts}
               AND NOT EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = file_publications.org_id AND e.user_id = file_publications.user_id AND j.status IN ('PENDING', 'RUNNING'))
               AND (status = 'STORED' OR EXISTS (SELECT 1 FROM file_publication_intents i
                    WHERE i.publication_id = file_publications.id AND NOT i.raw_write_required))
            """)
    int reclaimRecovery(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until,
            @Param("maxAttempts") int attempts);

    @Update(
            """
            UPDATE file_publications SET status = 'PUBLISHED', reserved_bytes = 0, version_id = #{versionId},
                   recovery_token = NULL, updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId} AND status = 'STORED'
               AND recovery_token = #{token} AND lease_until > #{now} AND recovery_deadline_at > #{now}
            """)
    int publishRecovered(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("versionId") UUID version,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            <script>UPDATE file_publications SET lease_until = #{now}, recovery_due_at = #{retryAt},
                   eligible_at = recovery_deadline_at, recovery_token = NULL
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND recoverable AND status IN ('STAGED', 'STORED') AND recovery_deadline_at > #{now}
               AND (NOT EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = file_publications.org_id AND e.user_id = file_publications.user_id AND j.status IN ('PENDING', 'RUNNING'))
                    OR EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = file_publications.org_id AND e.user_id = file_publications.user_id AND j.status = 'RUNNING' AND j.token = #{token} AND j.lease_until > #{now}))
               <choose><when test="token == null">AND recovery_token IS NULL</when>
                 <otherwise>AND recovery_token = #{token}</otherwise></choose></script>
            """)
    int deferRecovery(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("retryAt") OffsetDateTime retryAt);

    @Update(
            """
            UPDATE file_publications SET status = 'ABORTED', reserved_bytes = 0,
              recovery_token = NULL, eligible_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND recovery_token = #{token} AND status = 'STORED'
               AND NOT EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = file_publications.org_id AND e.user_id = file_publications.user_id AND j.status IN ('PENDING', 'RUNNING'))
            """)
    int abandonRecovery(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now);

    @Select(
            """
            <script>SELECT COALESCE(SUM(reserved_bytes), 0) FROM file_publications
             WHERE org_id = #{orgId} AND status IN ('STAGED', 'STORED') AND (lease_until > #{now} OR EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id AND j.org_id = e.org_id AND j.user_id = e.user_id WHERE e.publication_id = file_publications.id AND j.status IN ('PENDING', 'RUNNING') AND j.deadline_at > #{now}))
             <if test="userId != null">AND user_id = #{userId}</if>
             <if test="excludingId != null">AND id &lt;&gt; #{excludingId}</if>
            </script>
            """)
    long reserved(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("excludingId") UUID excluding,
            @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT COUNT(*) FROM file_publications WHERE org_id = #{orgId} AND user_id = #{userId}
              AND logical_path = #{path} AND ((status IN ('STAGED', 'STORED') AND lease_until > #{now}) OR EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id AND j.org_id = e.org_id AND j.user_id = e.user_id WHERE e.publication_id = file_publications.id AND j.status IN ('PENDING', 'RUNNING') AND j.deadline_at > #{now}))
            """)
    int pathBusy(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("path") String path,
            @Param("now") OffsetDateTime now);

    @Select(
            "SELECT COUNT(*) FROM file_publications WHERE org_id = #{orgId} AND user_id = #{userId}"
                + " AND logical_path = #{path} AND id <> #{excludingId} AND ((status IN ('STAGED',"
                + " 'STORED') AND lease_until > #{now}) OR EXISTS (SELECT 1 FROM"
                + " workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id AND"
                + " j.org_id = e.org_id AND j.user_id = e.user_id WHERE e.publication_id ="
                + " file_publications.id AND j.status IN ('PENDING', 'RUNNING') AND j.deadline_at >"
                + " #{now}))")
    int pathBusyExcept(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("path") String path,
            @Param("excludingId") UUID excluding,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE file_publications SET status = 'STORED', updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND status = 'STAGED' AND lease_until > #{now} AND recovery_token IS NULL
            """)
    int stored(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("now") OffsetDateTime now);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM file_publications WHERE id = #{id} AND org_id = #{orgId} AND user_id ="
                    + " #{userId} FOR UPDATE")
    List<Publication> lockOwned(
            @Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Update(
            """
            UPDATE file_publications SET status = 'PUBLISHED', reserved_bytes = 0,
                   version_id = #{versionId}, updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND status = 'STORED' AND lease_until > #{now} AND recovery_token IS NULL
            """)
    int published(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("versionId") UUID version,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE file_publications SET status = 'ABORTED', reserved_bytes = 0, eligible_at = #{eligibleAt}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId} AND status IN ('STAGED', 'STORED') AND recovery_token IS NULL
               AND NOT EXISTS (SELECT 1 FROM workspace_archive_items e JOIN workspace_archive_jobs j ON j.id = e.job_id
                    WHERE e.publication_id = file_publications.id AND e.org_id = file_publications.org_id AND e.user_id = file_publications.user_id AND j.status IN ('PENDING', 'RUNNING'))
            """)
    int abort(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("eligibleAt") OffsetDateTime eligibleAt);
}
