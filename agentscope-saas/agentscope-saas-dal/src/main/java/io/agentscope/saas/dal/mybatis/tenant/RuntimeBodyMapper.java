/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.memory.RuntimeBodyRepository.Body;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RuntimeBodyMapper {
    String COLUMNS =
            "id, org_id, user_id, agent_id, session_id, object_key, backend, sha256, size_bytes,"
                    + " status, eligible_at, claim_token, attempts";
    String OWNED =
            "id = #{id} AND org_id = #{scope.orgId} AND user_id = #{scope.userId} AND agent_id ="
                    + " #{scope.agentId} AND session_id = #{scope.sessionId}";

    @Insert(
            """
            INSERT INTO runtime_message_bodies(id, org_id, user_id, agent_id, session_id, object_key,
                backend, sha256, size_bytes, status, eligible_at)
            VALUES(#{body.id}, #{body.orgId}, #{body.userId}, #{body.agentId}, #{body.sessionId}, #{body.objectKey},
                #{body.backend}, #{body.sha256}, #{body.sizeBytes}, 'STAGED', #{body.eligibleAt})
            """)
    int stage(@Param("body") Body body);

    @Update(
            "UPDATE runtime_message_bodies SET status = 'READY', eligible_at = #{eligibleAt},"
                    + " updated_at = CURRENT_TIMESTAMP WHERE "
                    + OWNED
                    + " AND status = 'STAGED' AND eligible_at > #{now}")
    int ready(
            @Param("scope") Scope scope,
            @Param("id") UUID id,
            @Param("now") OffsetDateTime now,
            @Param("eligibleAt") OffsetDateTime eligibleAt);

    // Updating eligibility also fences a GC UPDATE that was waiting on this publication's row lock.
    @Update(
            "UPDATE runtime_message_bodies SET eligible_at = #{eligibleAt}, updated_at ="
                    + " CURRENT_TIMESTAMP WHERE "
                    + OWNED
                    + " AND status = 'READY'")
    int attach(
            @Param("scope") Scope scope,
            @Param("id") UUID id,
            @Param("eligibleAt") OffsetDateTime eligibleAt);

    @Select("SELECT " + COLUMNS + " FROM runtime_message_bodies WHERE " + OWNED)
    List<Body> findOwned(@Param("scope") Scope scope, @Param("id") UUID id);

    @Select(
            "<script>SELECT id FROM orgs WHERE id = #{scope.orgId} "
                    + TenantDirectoryMapper.QUOTA_LOCK
                    + "</script>")
    List<UUID> lockQuota(@Param("scope") Scope scope);

    @Select(
            """
            <script>SELECT COALESCE(SUM(size_bytes), 0) FROM runtime_message_bodies
             WHERE org_id = #{orgId} AND status != 'DELETED'
             <if test="userId != null">AND user_id = #{userId}</if></script>
            """)
    long usage(@Param("orgId") UUID orgId, @Param("userId") UUID userId);
}
