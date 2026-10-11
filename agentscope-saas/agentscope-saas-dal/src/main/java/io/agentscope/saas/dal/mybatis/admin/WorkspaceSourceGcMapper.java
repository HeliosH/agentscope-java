/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Source;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceSourceGcMapper {
    String ELIGIBLE =
            " eligible_at <= #{now} AND (lease_until IS NULL OR lease_until <= #{now}) AND NOT"
                + " EXISTS (SELECT 1 FROM workspace_archive_jobs j WHERE j.manifest_id ="
                + " workspace_sources.manifest_id AND j.org_id = workspace_sources.org_id AND"
                + " j.user_id = workspace_sources.user_id AND j.status IN ('PENDING', 'RUNNING')"
                + " AND j.deadline_at > #{now}) AND NOT EXISTS (SELECT 1 FROM"
                + " workspace_provider_retentions r WHERE r.source_id = workspace_sources.id AND"
                + " r.org_id = workspace_sources.org_id AND r.status <> 'CLEANED')";

    @Select(
            "SELECT * FROM workspace_sources WHERE "
                    + ELIGIBLE
                    + " ORDER BY eligible_at LIMIT #{limit}")
    List<Source> candidates(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Update(
            "UPDATE workspace_sources SET status = 'COLLECTING', token = #{token}, lease_until ="
                + " #{until}, attempts = attempts + 1 WHERE id = #{s.id} AND org_id = #{s.orgId}"
                + " AND user_id = #{s.userId} AND "
                    + ELIGIBLE)
    int claim(
            @Param("s") Source source,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until);

    @Update(
            "UPDATE workspace_sources SET status = 'COLLECTED', token = NULL, lease_until = NULL,"
                + " eligible_at = #{retryAt} WHERE id = #{s.id} AND org_id = #{s.orgId} AND status"
                + " = 'COLLECTING' AND token = #{token}")
    int collected(
            @Param("s") Source source,
            @Param("token") UUID token,
            @Param("retryAt") OffsetDateTime retryAt);

    @Update(
            "UPDATE workspace_sources SET token = NULL, lease_until = NULL, eligible_at ="
                + " #{retryAt} WHERE id = #{s.id} AND org_id = #{s.orgId} AND status = 'COLLECTING'"
                + " AND token = #{token}")
    int failed(
            @Param("s") Source source,
            @Param("token") UUID token,
            @Param("retryAt") OffsetDateTime retryAt);
}
