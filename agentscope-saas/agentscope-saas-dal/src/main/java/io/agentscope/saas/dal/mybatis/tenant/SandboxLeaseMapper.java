/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.NewSandboxLease;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant MyBatis mapper for orchestration sandbox lease lifecycle transitions. */
public interface SandboxLeaseMapper {

    String COLUMNS =
            """
            SELECT id, org_id, user_id, run_id, task_id, attempt_id, provider_id,
                   provider_sandbox_id, provider_state_json, image_or_template,
                   capabilities_json, workspace_snapshot_uri, workspace_version, status,
                   lease_owner, lease_expires_at, last_heartbeat_at, created_at, released_at,
                   release_error
              FROM sandbox_leases
            """;

    @Insert(
            """
            INSERT INTO sandbox_leases
                (id, org_id, user_id, run_id, task_id, attempt_id, provider_id,
                 image_or_template, capabilities_json, status, lease_owner, lease_expires_at,
                 last_heartbeat_at, created_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{runId}, #{taskId}, #{attemptId}, #{providerId},
                 #{imageOrTemplate},
                 #{capabilitiesJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 'PROVISIONING', #{leaseOwner}, #{leaseExpiresAt}, #{createdAt}, #{createdAt})
            """)
    int insert(NewSandboxLease lease);

    @Select(COLUMNS + " WHERE id = #{leaseId} AND org_id = #{orgId}")
    List<SandboxLeaseData> findById(@Param("leaseId") UUID leaseId, @Param("orgId") UUID orgId);

    @Select(
            COLUMNS
                    + """
                     WHERE attempt_id = #{attemptId} AND org_id = #{orgId}
                     ORDER BY created_at DESC
                     LIMIT 1
                    """)
    List<SandboxLeaseData> findByAttemptId(
            @Param("attemptId") UUID attemptId, @Param("orgId") UUID orgId);

    @Update(
            """
            UPDATE sandbox_leases
               SET provider_sandbox_id = #{providerSandboxId},
                   provider_state_json =
                       #{providerStateJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   status = 'ACTIVE',
                   last_heartbeat_at = #{heartbeatAt},
                   lease_expires_at = #{leaseExpiresAt},
                   release_error = NULL
             WHERE id = #{leaseId}
               AND org_id = #{orgId}
               AND status IN ('PROVISIONING', 'ACTIVE')
            """)
    int activate(
            @Param("leaseId") UUID leaseId,
            @Param("orgId") UUID orgId,
            @Param("providerSandboxId") String providerSandboxId,
            @Param("providerStateJson") String providerStateJson,
            @Param("heartbeatAt") OffsetDateTime heartbeatAt,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    @Update(
            """
            UPDATE sandbox_leases
               SET last_heartbeat_at = #{heartbeatAt},
                   lease_expires_at = #{leaseExpiresAt}
             WHERE id = #{leaseId}
               AND org_id = #{orgId}
               AND status IN ('PROVISIONING', 'ACTIVE', 'CHECKPOINTING')
            """)
    int heartbeat(
            @Param("leaseId") UUID leaseId,
            @Param("orgId") UUID orgId,
            @Param("heartbeatAt") OffsetDateTime heartbeatAt,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    @Update(
            """
            UPDATE sandbox_leases
               SET workspace_snapshot_uri = #{workspaceSnapshotUri},
                   workspace_version = #{workspaceVersion}
             WHERE id = #{leaseId}
               AND org_id = #{orgId}
               AND status IN ('PROVISIONING', 'ACTIVE', 'CHECKPOINTING', 'RELEASED')
            """)
    int checkpoint(
            @Param("leaseId") UUID leaseId,
            @Param("orgId") UUID orgId,
            @Param("workspaceSnapshotUri") String workspaceSnapshotUri,
            @Param("workspaceVersion") String workspaceVersion);

    @Update(
            """
            UPDATE sandbox_leases
               SET status = 'RELEASED',
                   released_at = #{releasedAt},
                   lease_expires_at = #{releasedAt},
                   last_heartbeat_at = #{releasedAt},
                   release_error = NULL
             WHERE id = #{leaseId}
               AND org_id = #{orgId}
               AND status IN ('PROVISIONING', 'ACTIVE', 'CHECKPOINTING')
            """)
    int release(
            @Param("leaseId") UUID leaseId,
            @Param("orgId") UUID orgId,
            @Param("releasedAt") OffsetDateTime releasedAt);

    @Update(
            """
            UPDATE sandbox_leases
               SET status = 'RELEASED',
                   released_at = #{releasedAt},
                   lease_expires_at = #{releasedAt},
                   last_heartbeat_at = #{releasedAt},
                   release_error = #{error}
             WHERE id = #{leaseId}
               AND org_id = #{orgId}
               AND status = 'PROVISIONING'
            """)
    int releaseAfterProvisioningFailure(
            @Param("leaseId") UUID leaseId,
            @Param("orgId") UUID orgId,
            @Param("releasedAt") OffsetDateTime releasedAt,
            @Param("error") String error);
}
