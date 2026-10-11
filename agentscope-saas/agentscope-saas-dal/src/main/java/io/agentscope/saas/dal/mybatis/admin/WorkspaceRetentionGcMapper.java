/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository.Retention;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceRetentionGcMapper {
    String DUE =
            """
            status <> 'CLEANED' AND (lease_until IS NULL OR lease_until <= #{now}) AND (
              deadline_at <= #{now}
              OR EXISTS (SELECT 1 FROM workspace_archive_jobs j WHERE j.manifest_id = workspace_provider_retentions.manifest_id
                 AND j.org_id = workspace_provider_retentions.org_id AND j.user_id = workspace_provider_retentions.user_id
                 AND j.status IN ('COMPLETED','FAILED','CANCELLED'))
              OR EXISTS (SELECT 1 FROM workspace_publication_manifests m WHERE m.id = workspace_provider_retentions.manifest_id
                 AND m.org_id = workspace_provider_retentions.org_id AND m.user_id = workspace_provider_retentions.user_id
                 AND (m.status = 'READY' OR NOT EXISTS (SELECT 1 FROM assistant_runs r JOIN chat_sessions s ON s.id = r.session_id
                   AND s.org_id = r.org_id AND s.user_id = r.user_id AND s.agent_id = r.agent_id
                   WHERE r.id = m.run_id AND r.org_id = m.org_id AND r.user_id = m.user_id AND r.agent_id = m.agent_id
                     AND r.status = 'RUNNING' AND NOT r.cancel_requested AND r.session_generation = s.execution_generation)))
            )
            """;

    @Select(
            "SELECT * FROM workspace_provider_retentions WHERE "
                    + DUE
                    + " ORDER BY deadline_at LIMIT #{limit}")
    List<Retention> due(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Update(
            "UPDATE workspace_provider_retentions SET status = 'CLEANING', token = #{token},"
                + " lease_until = #{until} WHERE source_id = #{r.sourceId} AND org_id = #{r.orgId}"
                + " AND "
                    + DUE)
    int claim(
            @Param("r") Retention value,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until);

    @Update(
            "UPDATE workspace_provider_retentions SET status = 'CLEANED', token = NULL, lease_until"
                    + " = NULL WHERE source_id = #{r.sourceId} AND org_id = #{r.orgId} AND status ="
                    + " 'CLEANING' AND token = #{token}")
    int cleaned(@Param("r") Retention value, @Param("token") UUID token);

    @Update(
            "UPDATE sandboxes SET retention_held = FALSE WHERE sandbox_type = #{providerId} AND"
                    + " org_id = #{orgId} AND user_id = #{userId} AND external_id = #{resourceId}")
    int releaseTracking(Retention value);

    @Update(
            "UPDATE sandbox_leases SET retention_held = FALSE WHERE provider_id = #{providerId} AND"
                    + " org_id = #{orgId} AND user_id = #{userId} AND provider_sandbox_id ="
                    + " #{resourceId}")
    int releaseLease(Retention value);
}
