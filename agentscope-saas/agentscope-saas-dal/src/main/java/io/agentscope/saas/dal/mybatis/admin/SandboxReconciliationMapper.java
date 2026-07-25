/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository.SandboxPoolCount;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository.SandboxTypeCount;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative MyBatis mapper for cross-tenant sandbox reconciliation. */
public interface SandboxReconciliationMapper {

    @Select(
            """
            SELECT sandbox_type, status, COUNT(*) AS count
              FROM sandboxes
             GROUP BY sandbox_type, status
            """)
    @ConstructorArgs({
        @Arg(column = "sandbox_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "count", javaType = long.class)
    })
    List<SandboxPoolCount> countByTypeAndStatus();

    @Select(
            """
            SELECT sandbox_type, COUNT(*) AS count
              FROM sandboxes
             WHERE status = 'active' AND expires_at < #{now}
             GROUP BY sandbox_type
            """)
    @ConstructorArgs({
        @Arg(column = "sandbox_type", javaType = String.class),
        @Arg(column = "count", javaType = long.class)
    })
    List<SandboxTypeCount> countExpiredActiveByType(@Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT id, org_id, user_id, sandbox_type, external_id
              FROM sandboxes
             WHERE status = 'active'
               AND expires_at IS NOT NULL
               AND expires_at < #{staleBefore}
             ORDER BY expires_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "sandbox_type", javaType = String.class),
        @Arg(column = "external_id", javaType = String.class)
    })
    List<SandboxResourceData> findExpiredActive(
            @Param("staleBefore") OffsetDateTime staleBefore, @Param("limit") int limit);

    @Select(
            """
            SELECT id, org_id, user_id, sandbox_type, external_id
              FROM sandboxes
             WHERE status IN ('evicted', 'released')
               AND external_id IS NOT NULL
               AND TRIM(external_id) <> ''
               AND COALESCE(backend_release_attempts, 0) < #{maxAttempts}
               AND (
                    backend_release_status IS NULL
                    OR backend_release_status IN ('pending', 'failed')
               )
             ORDER BY last_used_at ASC, created_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "sandbox_type", javaType = String.class),
        @Arg(column = "external_id", javaType = String.class)
    })
    List<SandboxResourceData> findBackendReleaseCandidates(
            @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Update(
            """
            UPDATE sandboxes
               SET status = 'evicted',
                   last_used_at = #{changedAt},
                   expires_at = #{changedAt},
                   backend_release_status = 'pending',
                   backend_release_error = NULL
             WHERE id = #{sandboxId} AND status = 'active'
            """)
    int markExpiredActiveEvicted(
            @Param("sandboxId") UUID sandboxId, @Param("changedAt") OffsetDateTime changedAt);

    @Update(
            """
            UPDATE sandboxes
               SET backend_release_status = 'terminating',
                   backend_release_error = NULL
             WHERE id = #{sandboxId}
               AND status IN ('evicted', 'released')
               AND COALESCE(backend_release_attempts, 0) < #{maxAttempts}
               AND (
                    backend_release_status IS NULL
                    OR backend_release_status IN ('pending', 'failed')
               )
            """)
    int claimBackendRelease(
            @Param("sandboxId") UUID sandboxId, @Param("maxAttempts") int maxAttempts);

    @Update(
            """
            UPDATE sandboxes
               SET backend_release_status = #{status},
                   backend_release_attempts =
                       COALESCE(backend_release_attempts, 0) + #{attemptIncrement},
                   backend_released_at =
                       CASE
                           WHEN #{releasedAt} IS NOT NULL THEN #{releasedAt}
                           ELSE backend_released_at
                       END,
                   backend_release_error = #{error}
             WHERE id = #{sandboxId}
            """)
    int recordBackendRelease(
            @Param("sandboxId") UUID sandboxId,
            @Param("status") String status,
            @Param("attemptIncrement") int attemptIncrement,
            @Param("releasedAt") OffsetDateTime releasedAt,
            @Param("error") String error);
}
