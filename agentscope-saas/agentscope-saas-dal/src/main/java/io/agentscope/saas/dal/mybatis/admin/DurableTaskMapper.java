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

/** MyBatis mapper for durable task read and delivery state. */
public interface DurableTaskMapper {

    String TASK_PROJECTION =
            """
            SELECT t.id, t.run_id, r.agent_id, t.external_task_id, t.status, t.output_json,
                   t.last_error_message AS error_message, t.created_at, t.completed_at,
                   t.delivered_at, COALESCE(ar.agent_type, 'assistant') AS agent_type
              FROM task_nodes t
              JOIN assistant_runs r ON r.id = t.run_id
              LEFT JOIN agent_runs ar ON ar.id = t.owner_agent_run_id
             WHERE t.org_id = #{orgId}
               AND r.user_id = #{userId}
               AND r.session_id = #{sessionId}
               AND t.external_task_id IS NOT NULL
            """;

    @Select(
            TASK_PROJECTION
                    + """
                       AND t.external_task_id = #{externalTaskId}
                     ORDER BY t.created_at DESC
                     LIMIT 1
                    """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "output_json", javaType = String.class),
        @Arg(column = "error_message", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class),
        @Arg(column = "delivered_at", javaType = OffsetDateTime.class)
    })
    List<DurableTaskData> findLatest(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId,
            @Param("externalTaskId") String externalTaskId);

    @Select(TASK_PROJECTION + " ORDER BY t.created_at ASC")
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "output_json", javaType = String.class),
        @Arg(column = "error_message", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class),
        @Arg(column = "delivered_at", javaType = OffsetDateTime.class)
    })
    List<DurableTaskData> findAll(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId);

    @Select(
            TASK_PROJECTION
                    + """
                       AND t.status IN ('SUCCEEDED','FAILED','CANCELLED','MANUAL_ACTION')
                       AND t.delivered_at IS NULL
                     ORDER BY t.completed_at ASC
                    """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "agent_id", javaType = UUID.class),
        @Arg(column = "external_task_id", javaType = String.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "output_json", javaType = String.class),
        @Arg(column = "error_message", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "completed_at", javaType = OffsetDateTime.class),
        @Arg(column = "delivered_at", javaType = OffsetDateTime.class)
    })
    List<DurableTaskData> findPendingDeliveries(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId);

    @Update(
            """
            UPDATE task_nodes
               SET delivered_at = COALESCE(delivered_at, #{deliveredAt})
             WHERE id IN (
                   SELECT t.id
                     FROM task_nodes t
                     JOIN assistant_runs r ON r.id = t.run_id
                    WHERE t.org_id = #{orgId}
                      AND r.user_id = #{userId}
                      AND r.session_id = #{sessionId}
                      AND t.external_task_id = #{externalTaskId}
             )
            """)
    int markDelivered(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId,
            @Param("externalTaskId") String externalTaskId,
            @Param("deliveredAt") OffsetDateTime deliveredAt);
}
