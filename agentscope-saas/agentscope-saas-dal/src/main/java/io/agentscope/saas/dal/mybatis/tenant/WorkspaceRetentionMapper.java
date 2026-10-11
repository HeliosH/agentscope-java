/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository.Retention;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkspaceRetentionMapper {
    @Select(
            """
            SELECT t.id AS tracking_id, l.id AS sandbox_lease_id FROM sandbox_leases l JOIN sandboxes t
              ON t.org_id = l.org_id AND t.user_id = l.user_id AND t.sandbox_type = l.provider_id
             WHERE l.org_id = #{s.orgId} AND l.user_id = #{s.userId} AND l.run_id = #{s.runId}
               AND l.task_id = #{s.execution.taskId} AND l.attempt_id = #{s.execution.attemptId}
               AND l.provider_id = #{providerId} AND l.provider_sandbox_id = #{resourceId}
               AND t.external_id = #{resourceId} AND t.agent_id = #{s.agentId} AND t.session_id = #{sessionId}
               AND l.status IN ('ACTIVE','RELEASED') AND t.status IN ('active','released')
               AND NOT l.retention_held AND NOT t.retention_held
               AND (t.backend_release_status IS NULL OR t.backend_release_status = 'pending')
             ORDER BY l.created_at DESC, t.created_at DESC LIMIT 1
            """)
    List<io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository.Binding> binding(
            @Param("s") io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope scope,
            @Param("sessionId") String sessionId,
            @Param("providerId") String providerId,
            @Param("resourceId") String resourceId);

    @Select(
            "SELECT COUNT(*) FROM workspace_provider_retentions WHERE org_id = #{orgId} AND status"
                    + " <> 'CLEANED'")
    long countHeld(UUID orgId);

    @Select(
            "SELECT COALESCE(backend_release_status, 'pending') FROM sandboxes WHERE org_id ="
                    + " #{orgId} AND user_id = #{userId} AND sandbox_type = #{providerId} AND"
                    + " external_id = #{resourceId} ORDER BY id FOR UPDATE")
    List<String> lockTrackingResource(Retention value);

    @Select(
            "SELECT status FROM sandbox_leases WHERE org_id = #{orgId} AND user_id = #{userId} AND"
                + " provider_id = #{providerId} AND provider_sandbox_id = #{resourceId} ORDER BY id"
                + " FOR UPDATE")
    List<String> lockLeaseResource(Retention value);

    @Update(
            "UPDATE sandboxes SET retention_held = TRUE WHERE org_id = #{orgId} AND user_id ="
                    + " #{userId} AND sandbox_type = #{providerId} AND external_id = #{resourceId}")
    int holdRelatedTracking(Retention value);

    @Update(
            "UPDATE sandbox_leases SET retention_held = TRUE WHERE org_id = #{orgId} AND user_id ="
                    + " #{userId} AND provider_id = #{providerId} AND provider_sandbox_id ="
                    + " #{resourceId}")
    int holdRelatedLeases(Retention value);

    @Update(
            """
              UPDATE sandboxes SET retention_held = TRUE WHERE id = #{trackingId} AND org_id = #{orgId}
                AND user_id = #{userId} AND sandbox_type = #{providerId} AND external_id = #{resourceId}
            AND status IN ('active','released') AND NOT retention_held
                AND (backend_release_status IS NULL OR backend_release_status = 'pending')
            """)
    int holdTracking(Retention value);

    @Update(
            """
              UPDATE sandbox_leases SET retention_held = TRUE WHERE id = #{sandboxLeaseId} AND org_id = #{orgId}
                AND user_id = #{userId} AND provider_id = #{providerId} AND provider_sandbox_id = #{resourceId}
            AND status IN ('ACTIVE','RELEASED') AND NOT retention_held
                AND EXISTS (SELECT 1 FROM workspace_publication_manifests m WHERE m.id = #{manifestId}
                  AND m.org_id = sandbox_leases.org_id AND m.user_id = sandbox_leases.user_id
                  AND m.run_id = sandbox_leases.run_id AND m.task_id = sandbox_leases.task_id
                  AND m.attempt_id = sandbox_leases.attempt_id)
            """)
    int holdLease(Retention value);

    @Insert(
            """
            INSERT INTO workspace_provider_retentions
              (source_id, org_id, user_id, manifest_id, tracking_id, sandbox_lease_id, provider_id, resource_id, status, deadline_at)
            VALUES (#{sourceId}, #{orgId}, #{userId}, #{manifestId}, #{trackingId}, #{sandboxLeaseId}, #{providerId}, #{resourceId}, 'STAGING', #{deadlineAt})
            """)
    int stage(Retention value);

    @Update(
            "UPDATE workspace_provider_retentions SET status = 'RETAINED' WHERE source_id ="
                + " #{r.sourceId} AND org_id = #{r.orgId} AND user_id = #{r.userId} AND status ="
                + " 'STAGING' AND deadline_at > #{now}")
    int ready(@Param("r") Retention value, @Param("now") OffsetDateTime now);

    @Select(
            "SELECT * FROM workspace_provider_retentions WHERE source_id = #{sourceId} AND org_id ="
                    + " #{orgId} AND user_id = #{userId} FOR UPDATE")
    List<Retention> owned(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sourceId") UUID sourceId);
}
