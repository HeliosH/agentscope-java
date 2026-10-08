/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Recovery;
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

    @Update(
            """
            UPDATE file_publications SET status = 'STORED', recovery_token = #{token},
              recovery_attempts = recovery_attempts + 1, lease_until = #{until}, eligible_at = #{until}, updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
               AND recoverable AND status IN ('STAGED', 'STORED') AND lease_until <= #{now}
               AND recovery_due_at <= #{now} AND recovery_deadline_at > #{now} AND recovery_attempts < #{maxAttempts}
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
             WHERE org_id = #{orgId} AND status IN ('STAGED', 'STORED') AND lease_until > #{now}
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
              AND logical_path = #{path} AND status IN ('STAGED', 'STORED') AND lease_until > #{now}
            """)
    int pathBusy(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("path") String path,
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
            """)
    int abort(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("id") UUID id,
            @Param("eligibleAt") OffsetDateTime eligibleAt);
}
