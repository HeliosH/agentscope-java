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

/** MyBatis mapper for lease-based orchestration outbox delivery. */
public interface OrchestrationOutboxMapper {

    @Select(
            """
            SELECT id, org_id, aggregate_id, aggregate_type, event_type, payload_json,
                   created_at, attempts
              FROM orchestration_outbox
             WHERE published_at IS NULL
               AND dead_lettered_at IS NULL
               AND attempts < #{maxAttempts}
               AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
               AND (locked_until IS NULL OR locked_until < #{now})
             ORDER BY created_at ASC, id ASC
             LIMIT #{batchSize}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "aggregate_id", javaType = UUID.class),
        @Arg(column = "aggregate_type", javaType = String.class),
        @Arg(column = "event_type", javaType = String.class),
        @Arg(column = "payload_json", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "attempts", javaType = int.class)
    })
    List<OrchestrationOutboxData> findClaimable(
            @Param("now") OffsetDateTime now,
            @Param("batchSize") int batchSize,
            @Param("maxAttempts") int maxAttempts);

    @Update(
            """
            UPDATE orchestration_outbox
               SET locked_by = #{workerId},
                   locked_until = #{leaseExpiresAt},
                   attempts = attempts + 1,
                   last_error = NULL
             WHERE id = #{id}
               AND published_at IS NULL
               AND dead_lettered_at IS NULL
               AND attempts < #{maxAttempts}
               AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
               AND (locked_until IS NULL OR locked_until < #{now})
            """)
    int claim(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("now") OffsetDateTime now,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt,
            @Param("maxAttempts") int maxAttempts);

    @Update(
            """
            UPDATE orchestration_outbox
               SET published_at = #{publishedAt},
                   locked_by = NULL,
                   locked_until = NULL,
                   next_attempt_at = NULL,
                   last_error = NULL
             WHERE id = #{id}
               AND locked_by = #{workerId}
               AND published_at IS NULL
            """)
    int markPublished(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("publishedAt") OffsetDateTime publishedAt);

    @Update(
            """
            UPDATE orchestration_outbox
               SET locked_by = NULL,
                   locked_until = NULL,
                   next_attempt_at = #{nextAttemptAt},
                   dead_lettered_at = #{deadLetteredAt},
                   last_error = #{error}
             WHERE id = #{id}
               AND locked_by = #{workerId}
               AND published_at IS NULL
            """)
    int markFailed(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
            @Param("deadLetteredAt") OffsetDateTime deadLetteredAt,
            @Param("error") String error);
}
