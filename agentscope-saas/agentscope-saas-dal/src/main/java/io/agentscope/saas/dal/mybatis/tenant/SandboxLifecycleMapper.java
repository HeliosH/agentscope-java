/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.SandboxEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for sandbox resource lifecycle and quota enforcement. */
public interface SandboxLifecycleMapper {

    String COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, session_id, sandbox_type, external_id, status,
                   created_at, last_used_at, expires_at, backend_release_status,
                   backend_release_attempts, backend_released_at, backend_release_error
              FROM sandboxes
            """;

    @Select(
            """
            SELECT COUNT(*) FROM sandboxes
             WHERE org_id = #{orgId} AND user_id = #{userId} AND status = #{status}
            """)
    int countOwnedByStatus(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status);

    @Select(
            COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND status = #{status}
                    """)
    List<SandboxEntity> findOwnedByStatus(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status);

    @Select(
            """
            <script>
            """
                    + COLUMNS
                    + """
                     WHERE org_id = #{orgId}
                     <if test="userId != null">AND user_id = #{userId}</if>
                     <if test="status != null">AND status = #{status}</if>
                     <if test="sandboxType != null">AND sandbox_type = #{sandboxType}</if>
                     <if test="expiredOnly">AND status = 'active' AND expires_at &lt; #{now}</if>
                     ORDER BY last_used_at DESC, created_at DESC LIMIT #{limit}
                    </script>
                    """)
    List<SandboxEntity> findAdminSandboxes(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("sandboxType") String sandboxType,
            @Param("expiredOnly") boolean expiredOnly,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    @Select(COLUMNS + " WHERE id = #{id}")
    List<SandboxEntity> findById(@Param("id") UUID id);

    @Insert(
            """
            INSERT INTO sandboxes
                (id, org_id, user_id, agent_id, session_id, sandbox_type, external_id, status,
                 last_used_at, expires_at, backend_release_status, backend_release_attempts,
                 backend_released_at, backend_release_error)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{sandboxType},
                 #{externalId}, #{status}, #{lastUsedAt}, #{expiresAt}, #{backendReleaseStatus},
                 #{backendReleaseAttempts}, #{backendReleasedAt}, #{backendReleaseError})
            """)
    int insert(SandboxEntity sandbox);

    @Update(
            """
            UPDATE sandboxes
               SET agent_id = #{agentId}, session_id = #{sessionId}, sandbox_type = #{sandboxType},
                   external_id = #{externalId}, status = #{status}, last_used_at = #{lastUsedAt},
                   expires_at = #{expiresAt}, backend_release_status = #{backendReleaseStatus},
                   backend_release_attempts = #{backendReleaseAttempts},
                   backend_released_at = #{backendReleasedAt},
                   backend_release_error = #{backendReleaseError}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int update(SandboxEntity sandbox);
}
