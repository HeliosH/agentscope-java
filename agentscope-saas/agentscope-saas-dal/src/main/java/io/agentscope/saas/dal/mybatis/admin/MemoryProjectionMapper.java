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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for memory ledger projection leases. */
public interface MemoryProjectionMapper {

    @Select(
            """
            SELECT id, org_id, user_id, agent_id, session_id, content_json, metadata_json
              FROM memory_events
             WHERE source = 'mem0'
               AND event_type = 'conversation'
               AND sync_attempts < #{maxAttempts}
               AND (
                    sync_status IN ('pending', 'failed')
                    OR (sync_status = 'syncing' AND updated_at < #{staleBefore})
               )
             ORDER BY created_at ASC
             LIMIT #{batchSize}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = String.class),
        @Arg(column = "session_id", javaType = String.class),
        @Arg(column = "content_json", javaType = String.class),
        @Arg(column = "metadata_json", javaType = String.class)
    })
    List<MemoryProjectionData> findReplayable(
            @Param("batchSize") int batchSize,
            @Param("maxAttempts") int maxAttempts,
            @Param("staleBefore") OffsetDateTime staleBefore);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'syncing', last_error = NULL, updated_at = #{claimedAt}
             WHERE id = #{id}
               AND source = 'mem0'
               AND event_type = 'conversation'
               AND sync_attempts < #{maxAttempts}
               AND (
                    sync_status IN ('pending', 'failed')
                    OR (sync_status = 'syncing' AND updated_at < #{staleBefore})
               )
            """)
    int claim(
            @Param("id") UUID id,
            @Param("maxAttempts") int maxAttempts,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("claimedAt") OffsetDateTime claimedAt);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'synced',
                   sync_attempts = sync_attempts + 1,
                   synced_at = #{syncedAt},
                   last_error = NULL,
                   updated_at = #{syncedAt}
             WHERE id = #{id} AND sync_status = 'syncing'
            """)
    int markSynced(@Param("id") UUID id, @Param("syncedAt") OffsetDateTime syncedAt);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'failed',
                   sync_attempts = sync_attempts + 1,
                   last_error = #{error},
                   updated_at = #{failedAt}
             WHERE id = #{id} AND sync_status = 'syncing'
            """)
    int markFailed(
            @Param("id") UUID id,
            @Param("error") String error,
            @Param("failedAt") OffsetDateTime failedAt);
}
