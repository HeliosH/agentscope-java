/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Reference;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Resource;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SandboxUsageMapper {
    String RESOURCE =
            "SELECT"
                + " id,org_id,user_id,agent_id,run_id,session_id,session_generation,provider_id,deployment_key,slot_key,external_id,status,release_token,release_until,release_attempts,created_at"
                + " FROM sandbox_runtime_resources";
    String OWNER = "org_id = #{orgId} AND user_id = #{userId} AND id = #{id}";

    @Select(
            """
            SELECT COUNT(*) FROM assistant_runs r
            JOIN chat_sessions s ON s.id = r.session_id AND s.org_id = r.org_id AND s.user_id = r.user_id
            JOIN run_attempts a ON a.run_id = r.id AND a.org_id = r.org_id
            WHERE r.id = #{scope.runId} AND r.org_id = #{scope.orgId} AND r.user_id = #{scope.userId}
                AND r.agent_id = #{scope.agentId} AND r.session_id = #{scope.sessionId}
                AND s.agent_id = #{scope.agentId} AND s.execution_generation = #{scope.sessionGeneration}
                AND r.session_generation = s.execution_generation AND r.status = 'RUNNING' AND NOT r.cancel_requested
                AND (r.deadline_at IS NULL OR r.deadline_at > CURRENT_TIMESTAMP)
                AND a.id = #{scope.attemptId} AND a.task_id = #{scope.taskId} AND a.status IN ('RUNNING','ARCHIVING')
                AND COALESCE(a.lease_owner,'') = COALESCE(#{scope.leaseOwner},'')
                AND (a.lease_expires_at IS NULL OR a.lease_expires_at > CURRENT_TIMESTAMP)
            """)
    long authority(@Param("scope") Scope scope);

    @Select(
            """
            SELECT (SELECT COUNT(*) FROM sandbox_runtime_resources r WHERE r.org_id = #{orgId}
                AND r.user_id = #{userId} AND r.status <> 'RELEASED')
                + (SELECT COUNT(*) FROM sandboxes s WHERE s.org_id = #{orgId} AND s.user_id = #{userId}
                    AND s.status = 'active' AND NOT EXISTS (SELECT 1 FROM sandbox_runtime_resources r
                        WHERE r.org_id = s.org_id AND r.user_id = s.user_id AND r.provider_id = s.sandbox_type
                            AND r.external_id = s.external_id AND r.status <> 'RELEASED'))
            """)
    long countOccupied(@Param("orgId") UUID org, @Param("userId") UUID user);

    @Select(
            "SELECT COUNT(*) FROM sandbox_runtime_resources WHERE org_id = #{orgId} AND user_id ="
                    + " #{userId} AND provider_id = #{provider} AND external_id = #{externalId} AND"
                    + " status <> 'RELEASED'")
    long managedBackend(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("provider") String provider,
            @Param("externalId") String externalId);

    @Select(
            """
            SELECT COUNT(*) FROM sandbox_runtime_resources WHERE org_id = #{orgId} AND user_id = #{userId}
                AND provider_id = #{provider} AND deployment_key = #{deployment} AND slot_key = #{slot} AND status <> 'RELEASED'
            """)
    long slotOccupied(
            @Param("orgId") UUID org,
            @Param("userId") UUID user,
            @Param("provider") String provider,
            @Param("deployment") String deployment,
            @Param("slot") String slot);

    @Insert(
            """
            INSERT INTO sandbox_runtime_resources(id,org_id,user_id,agent_id,run_id,session_id,session_generation,
                provider_id,deployment_key,slot_key,external_id,status,release_attempts,created_at,updated_at)
            VALUES(#{r.id},#{r.orgId},#{r.userId},#{r.agentId},#{r.runId},#{r.sessionId},#{r.sessionGeneration},
                #{r.providerId},#{r.deploymentKey},#{r.slotKey},NULL,'PROVISIONING',0,#{r.createdAt},#{r.createdAt})
            """)
    int insertResource(@Param("r") Resource resource);

    @Select(RESOURCE + " WHERE " + OWNER + " FOR UPDATE")
    List<Resource> lock(@Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Select(RESOURCE + " WHERE " + OWNER)
    List<Resource> find(@Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Insert(
            """
            INSERT INTO sandbox_usage_refs(id,resource_id,org_id,user_id,task_id,attempt_id,lease_owner,parent_id,status,expires_at)
            VALUES(#{ref.id},#{ref.resourceId},#{ref.orgId},#{ref.userId},#{ref.taskId},#{ref.attemptId},#{ref.leaseOwner},
                #{ref.parentId},'ACTIVE',#{ref.expiresAt})
            """)
    int insertReference(@Param("ref") Reference reference);

    @Select(
            """
            SELECT id,resource_id,org_id,user_id,task_id,attempt_id,lease_owner,parent_id,status,expires_at
            FROM sandbox_usage_refs WHERE org_id = #{orgId} AND user_id = #{userId} AND id = #{id}
            """)
    List<Reference> reference(
            @Param("orgId") UUID org, @Param("userId") UUID user, @Param("id") UUID id);

    @Select(
            "SELECT COUNT(*) FROM sandbox_usage_refs WHERE resource_id = #{id} AND status ="
                    + " 'ACTIVE' AND id <> #{exceptId}")
    long activeReferences(@Param("id") UUID id, @Param("exceptId") UUID exceptId);

    @Update(
            """
            UPDATE sandbox_runtime_resources SET external_id = #{externalId},
                status = CASE WHEN status = 'PROVISIONING' THEN 'ACTIVE' ELSE 'RELEASE_FAILED' END,
                release_token = NULL,release_until = NULL,updated_at = CURRENT_TIMESTAMP
            WHERE id = #{r.id} AND org_id = #{r.orgId} AND user_id = #{r.userId} AND external_id IS NULL
            """)
    int activate(@Param("r") Resource resource, @Param("externalId") String external);

    @Update(
            """
            UPDATE sandbox_runtime_resources SET status = #{status},release_token = #{token},release_until = #{until},
                release_attempts = release_attempts + CASE WHEN #{status} = 'RELEASING' THEN 1 ELSE 0 END,updated_at = CURRENT_TIMESTAMP
            WHERE id = #{r.id} AND org_id = #{r.orgId} AND user_id = #{r.userId} AND status = #{r.status}
            """)
    int state(
            @Param("r") Resource resource,
            @Param("status") String status,
            @Param("token") UUID token,
            @Param("until") OffsetDateTime until);

    @Update(
            """
            UPDATE sandbox_usage_refs SET expires_at = #{until},updated_at = CURRENT_TIMESTAMP
            WHERE id = #{ref.id} AND resource_id = #{ref.resourceId} AND org_id = #{ref.orgId} AND user_id = #{ref.userId}
                AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP
            """)
    int heartbeat(@Param("ref") Reference ref, @Param("until") OffsetDateTime until);

    @Update(
            """
            UPDATE sandbox_usage_refs SET status = 'CLOSED',updated_at = CURRENT_TIMESTAMP
            WHERE id = #{ref.id} AND resource_id = #{ref.resourceId} AND org_id = #{ref.orgId} AND user_id = #{ref.userId} AND status = 'ACTIVE'
            """)
    int closeReference(@Param("ref") Reference ref);

    @Update(
            """
            UPDATE sandbox_runtime_resources SET status = #{status},release_until = NULL,updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND org_id = #{orgId} AND release_token = #{token} AND status = 'RELEASING'
            """)
    int finish(
            @Param("orgId") UUID org,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("status") String status);
}
