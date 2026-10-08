/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.RunArtifactRepository.NewRunArtifact;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Tenant MyBatis mapper for orchestration artifacts. */
public interface RunArtifactMapper {

    @Select(
            """
            SELECT s.id FROM chat_sessions s
             WHERE s.org_id = #{orgId} AND EXISTS (
                 SELECT 1 FROM assistant_runs r JOIN task_nodes t ON t.run_id = r.id AND t.org_id = r.org_id
                   JOIN run_attempts a ON a.run_id = r.id AND a.task_id = t.id AND a.org_id = r.org_id
                  WHERE r.id = #{runId} AND t.id = #{taskId} AND a.id = #{attemptId}
                    AND r.org_id = s.org_id AND r.user_id = s.user_id AND r.agent_id = s.agent_id
                    AND r.session_id = s.id AND r.session_generation = s.execution_generation)
             FOR UPDATE
            """)
    List<UUID> lockCurrentPublicationScope(
            @Param("orgId") UUID orgId,
            @Param("runId") UUID runId,
            @Param("taskId") UUID taskId,
            @Param("attemptId") UUID attemptId);

    @Select("SELECT COUNT(*) FROM run_artifacts WHERE id = #{id} AND org_id = #{orgId}")
    int countById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO run_artifacts
                (id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                 logical_path, artifact_type, evidence_json, created_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{taskId}, #{attemptId}, #{fileId}, #{fileVersionId},
                 #{logicalPath}, #{artifactType},
                 #{evidenceJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{createdAt})
            """)
    int insert(NewRunArtifact artifact);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                   logical_path, artifact_type, evidence_json, created_at
              FROM run_artifacts
             WHERE run_id = #{runId}
               AND org_id = #{orgId}
             ORDER BY created_at, id
            """)
    List<RunArtifactData> findByRunId(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Select(
            """
            <script>
            SELECT id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                   logical_path, artifact_type, evidence_json, created_at
              FROM run_artifacts
             WHERE org_id = #{orgId}
               AND run_id IN
               <foreach collection="runIds" item="runId" open="(" separator="," close=")">
                 #{runId}
               </foreach>
             ORDER BY run_id, created_at, id
            </script>
            """)
    List<RunArtifactData> findByRunIds(
            @Param("runIds") List<UUID> runIds, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                   logical_path, artifact_type, evidence_json, created_at
              FROM run_artifacts
             WHERE attempt_id = #{attemptId}
               AND org_id = #{orgId}
             ORDER BY logical_path, created_at, id
            """)
    List<RunArtifactData> findByAttemptId(
            @Param("attemptId") UUID attemptId, @Param("orgId") UUID orgId);
}
