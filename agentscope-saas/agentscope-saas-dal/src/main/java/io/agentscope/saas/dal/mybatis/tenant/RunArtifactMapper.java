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

    @Select("SELECT COUNT(*) FROM run_artifacts WHERE id = #{id} AND org_id = #{orgId}")
    int countById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO run_artifacts
                (id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                 artifact_type, evidence_json, created_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{taskId}, #{attemptId}, #{fileId}, #{fileVersionId},
                 #{artifactType},
                 #{evidenceJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{createdAt})
            """)
    int insert(NewRunArtifact artifact);

    @Select(
            """
            SELECT id, org_id, run_id, task_id, attempt_id, file_id, file_version_id,
                   artifact_type, evidence_json, created_at
              FROM run_artifacts
             WHERE run_id = #{runId}
               AND org_id = #{orgId}
             ORDER BY created_at, id
            """)
    List<RunArtifactData> findByRunId(@Param("runId") UUID runId, @Param("orgId") UUID orgId);
}
