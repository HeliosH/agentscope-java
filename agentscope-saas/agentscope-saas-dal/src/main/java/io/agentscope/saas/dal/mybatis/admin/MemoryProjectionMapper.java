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

/** System queue scan; mutations additionally bind the owning organization and claim token. */
public interface MemoryProjectionMapper {

    String ELIGIBLE =
            """
            AND source = 'mem0' AND event_type = 'conversation'
            AND (
                (sync_status IN ('pending', 'failed')
                 AND (sync_next_attempt_at IS NULL OR sync_next_attempt_at <= #{now}))
                OR (sync_status = 'syncing'
                    AND ((sync_lease_until IS NOT NULL AND sync_lease_until <= #{now})
                         OR (sync_lease_until IS NULL AND updated_at <= #{legacyStaleBefore})))
            )
            """;

    @Select(
            """
            SELECT id, org_id, user_id, agent_id, session_id, content_json, metadata_json,
                   sync_attempts AS attempts
              FROM memory_events
             WHERE 1 = 1
            """
                    + ELIGIBLE
                    + " ORDER BY created_at, id LIMIT #{batchSize}")
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = String.class),
        @Arg(column = "session_id", javaType = String.class),
        @Arg(column = "content_json", javaType = String.class),
        @Arg(column = "metadata_json", javaType = String.class),
        @Arg(column = "attempts", javaType = int.class)
    })
    List<MemoryProjectionData> findReplayable(
            @Param("batchSize") int batchSize,
            @Param("now") OffsetDateTime now,
            @Param("legacyStaleBefore") OffsetDateTime legacyStaleBefore);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'syncing', sync_claim_token = #{token},
                   sync_lease_until = #{leaseUntil}, sync_next_attempt_at = NULL,
                   sync_attempts = sync_attempts + 1, last_error = NULL, updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId}
               AND sync_attempts = #{expectedAttempts} AND sync_attempts < #{maxAttempts}
            """
                    + ELIGIBLE)
    int claim(
            @Param("orgId") UUID orgId,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("legacyStaleBefore") OffsetDateTime legacyStaleBefore);

    String OWNED =
            """
             WHERE id = #{id} AND org_id = #{orgId}
               AND source = 'mem0' AND event_type = 'conversation'
               AND sync_status = 'syncing' AND sync_claim_token = #{token}
               AND sync_lease_until > #{now}
            """;

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'synced', synced_at = #{now}, last_error = NULL,
                   sync_claim_token = NULL, sync_lease_until = NULL,
                   sync_next_attempt_at = NULL, updated_at = #{now}
            """
                    + OWNED)
    int markSynced(
            @Param("orgId") UUID orgId,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = CASE WHEN sync_attempts >= #{maxAttempts}
                                      THEN 'dead_letter' ELSE 'failed' END,
                   last_error = #{error}, sync_claim_token = NULL, sync_lease_until = NULL,
                   sync_next_attempt_at = CASE WHEN sync_attempts >= #{maxAttempts}
                                               THEN NULL ELSE #{nextAttemptAt} END,
                   updated_at = #{now}
            """
                    + OWNED)
    int markFailed(
            @Param("orgId") UUID orgId,
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("error") String error,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") OffsetDateTime now,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

    @Update(
            """
            UPDATE memory_events
               SET sync_status = 'dead_letter', sync_claim_token = NULL,
                   sync_lease_until = NULL, sync_next_attempt_at = NULL,
                   last_error = COALESCE(last_error, 'MEMORY_PROJECTION_ATTEMPTS_EXHAUSTED'),
                   updated_at = #{now}
             WHERE id = #{id} AND org_id = #{orgId} AND sync_attempts >= #{maxAttempts}
            """
                    + ELIGIBLE)
    int exhaust(
            @Param("orgId") UUID orgId,
            @Param("id") UUID id,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") OffsetDateTime now,
            @Param("legacyStaleBefore") OffsetDateTime legacyStaleBefore);
}
