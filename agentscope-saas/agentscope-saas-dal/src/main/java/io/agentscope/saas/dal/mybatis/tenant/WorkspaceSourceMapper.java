/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Baseline;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Source;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceSourceMapper {
    @Select(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM workspace_sources WHERE org_id = #{orgId} AND"
                    + " status <> 'COLLECTED'")
    long reserved(@Param("orgId") UUID org);

    @Insert(
            """
            INSERT INTO workspace_sources (id, org_id, user_id, manifest_id, object_key, backend, size_bytes, sha256,
               status, deadline_at, eligible_at, created_at)
            VALUES (#{id}, #{orgId}, #{userId}, #{manifestId}, #{objectKey}, #{backend}, #{sizeBytes}, #{sha256},
               'STAGING', #{deadlineAt}, #{eligibleAt}, #{createdAt})
            """)
    int stage(Source source);

    @Insert(
            """
            <script>INSERT INTO workspace_source_files (source_id, org_id, user_id, logical_path, base_file_id, base_version_id, base_status)
              VALUES <foreach collection="files" item="f" separator=",">
                (#{s.id}, #{s.orgId}, #{s.userId}, #{f.logicalPath}, #{f.fileId}, #{f.versionId}, #{f.status})
              </foreach></script>
            """)
    int stageFiles(@Param("s") Source source, @Param("files") List<Baseline> files);

    @Select(
            "SELECT logical_path, base_file_id AS file_id, base_version_id AS version_id,"
                + " base_status AS status FROM workspace_source_files WHERE source_id = #{id} AND"
                + " org_id = #{orgId} AND user_id = #{userId} ORDER BY logical_path")
    List<Baseline> baselines(Source source);

    @Select(
            "SELECT * FROM workspace_sources WHERE org_id = #{orgId} AND user_id = #{userId} AND"
                    + " manifest_id = #{manifestId} FOR UPDATE")
    List<Source> lockOwned(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("manifestId") UUID manifest);

    @Update(
            """
            UPDATE workspace_sources SET status = 'RETAINED'
              WHERE id = #{s.id} AND org_id = #{s.orgId} AND user_id = #{s.userId} AND manifest_id = #{s.manifestId}
                AND status = 'STAGING' AND deadline_at > #{now} AND token IS NULL
            """)
    int retain(@Param("s") Source source, @Param("now") OffsetDateTime now);
}
