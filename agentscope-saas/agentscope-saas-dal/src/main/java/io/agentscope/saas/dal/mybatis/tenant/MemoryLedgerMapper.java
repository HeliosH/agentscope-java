/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.MemoryEventEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for the durable memory event ledger. */
public interface MemoryLedgerMapper {

    String COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, session_id, source, event_type, content_json,
                   metadata_json, sync_status, sync_attempts, synced_at, last_error, created_at,
                   updated_at
              FROM memory_events
            """;

    @Select(
            COLUMNS
                    + """
                     WHERE sync_status = #{syncStatus}
                     ORDER BY created_at ASC, id ASC LIMIT 100
                    """)
    List<MemoryEventEntity> findBySyncStatus(@Param("syncStatus") String syncStatus);

    @Select(
            COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId}
                     ORDER BY created_at DESC, id DESC
                    """)
    List<MemoryEventEntity> findUserEvents(
            @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            """
            <script>
            """
                    + COLUMNS
                    + """
                     WHERE org_id = #{orgId}
                     <if test="userId != null">AND user_id = #{userId}</if>
                     <if test="sessionId != null">AND session_id = #{sessionId}</if>
                     <if test="syncStatus != null">AND sync_status = #{syncStatus}</if>
                     ORDER BY created_at DESC, id DESC LIMIT #{limit}
                    </script>
                    """)
    List<MemoryEventEntity> findAdminEvents(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") String sessionId,
            @Param("syncStatus") String syncStatus,
            @Param("limit") int limit);

    @Select(COLUMNS + " WHERE id = #{id}")
    List<MemoryEventEntity> findById(@Param("id") UUID id);

    @Insert(
            """
            INSERT INTO memory_events
                (id, org_id, user_id, agent_id, session_id, source, event_type, content_json,
                 metadata_json, sync_status, sync_attempts, synced_at, last_error, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{source}, #{eventType},
                 #{contentJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{metadataJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{syncStatus}, #{syncAttempts}, #{syncedAt}, #{lastError}, #{updatedAt})
            """)
    int insert(MemoryEventEntity event);

    @Update(
            """
            UPDATE memory_events
               SET source = #{source}, event_type = #{eventType},
                   content_json = #{contentJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   metadata_json = #{metadataJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   sync_status = #{syncStatus}, sync_attempts = #{syncAttempts},
                   synced_at = #{syncedAt}, last_error = #{lastError}, updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId}
            """)
    int update(MemoryEventEntity event);
}
