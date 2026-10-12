/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Resource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SandboxUsageGcMapper {
    String COLUMNS =
            "SELECT"
                + " r.id,r.org_id,r.user_id,r.agent_id,r.run_id,r.session_id,r.session_generation,r.provider_id,r.deployment_key,r.slot_key,r.external_id,r.status,r.release_token,r.release_until,r.release_attempts,r.created_at"
                + " FROM sandbox_runtime_resources r";
    String ELIGIBLE =
            """
            r.status <> 'RELEASED' AND (r.release_until IS NULL OR r.release_until < #{staleBefore})
            AND r.release_attempts < #{maxAttempts}
            AND NOT EXISTS (SELECT 1 FROM sandbox_usage_refs u WHERE u.resource_id = r.id AND u.status = 'ACTIVE' AND u.expires_at > #{staleBefore})
            AND NOT EXISTS (SELECT 1 FROM workspace_provider_retentions p WHERE p.org_id = r.org_id AND p.user_id = r.user_id
                AND p.provider_id = r.provider_id AND p.resource_id = r.external_id AND p.status <> 'CLEANED')
            """;

    @Select(
            COLUMNS
                    + " WHERE r.deployment_key = #{deployment} AND "
                    + ELIGIBLE
                    + " ORDER BY r.created_at,r.id LIMIT #{limit}")
    List<Resource> candidates(
            @Param("deployment") String deployment,
            @Param("staleBefore") OffsetDateTime stale,
            @Param("maxAttempts") int attempts,
            @Param("limit") int limit);

    @Select(COLUMNS + " WHERE r.id = #{id} FOR UPDATE")
    List<Resource> lock(@Param("id") UUID id);

    @Select(COLUMNS + " WHERE r.id = #{id} AND " + ELIGIBLE)
    List<Resource> eligible(
            @Param("id") UUID id,
            @Param("staleBefore") OffsetDateTime stale,
            @Param("maxAttempts") int attempts);

    @Update(
            "UPDATE sandbox_usage_refs SET status = 'EXPIRED',updated_at = CURRENT_TIMESTAMP WHERE"
                    + " resource_id = #{id} AND status = 'ACTIVE' AND expires_at <= #{staleBefore}")
    int expire(@Param("id") UUID id, @Param("staleBefore") OffsetDateTime stale);

    @Update(
            """
            UPDATE sandbox_runtime_resources SET status = 'RELEASING',release_token = #{token},release_until = #{until},
                release_attempts = release_attempts + 1,updated_at = CURRENT_TIMESTAMP WHERE id = #{id}
            """)
    int claim(
            @Param("id") UUID id, @Param("token") UUID token, @Param("until") OffsetDateTime until);

    @Select(COLUMNS + " WHERE r.id = #{id}")
    List<Resource> find(@Param("id") UUID id);

    @Update(
            """
            UPDATE sandbox_runtime_resources SET status = #{status},release_until = NULL,updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND release_token = #{token} AND status = 'RELEASING'
            """)
    int finish(@Param("id") UUID id, @Param("token") UUID token, @Param("status") String status);
}
