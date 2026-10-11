/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository.Presence;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WorkerPresenceMapper {
    @Select(
            """
            SELECT COUNT(*) AS count, MIN(t.updated_at) AS oldest_queued_at
            FROM task_nodes t JOIN assistant_runs r ON r.id = t.run_id AND r.org_id = t.org_id
            WHERE t.status = 'READY' AND t.recovery_count > 0 AND r.status = 'RUNNING'
                AND NOT r.cancel_requested
            """)
    io.agentscope.saas.domain.orchestration.WorkerPresenceRepository.WaitingRecovery
            waitingRecovery();

    @Update(
            """
            UPDATE worker_heartbeats SET capability_hash = #{capability}, available_slots = #{slots},
                max_archive_bytes = #{archiveBytes}, max_file_bytes = #{fileBytes},
                heartbeat_at = CURRENT_TIMESTAMP,
                expires_at = CURRENT_TIMESTAMP + CAST(#{ttl} AS BIGINT) * INTERVAL '1' SECOND
            WHERE deployment_scope = #{deployment} AND worker_id = #{worker}
            """)
    int heartbeat(
            @Param("deployment") String deployment,
            @Param("worker") UUID worker,
            @Param("capability") String capability,
            @Param("slots") int slots,
            @Param("ttl") long ttl,
            @Param("archiveBytes") long archiveBytes,
            @Param("fileBytes") long fileBytes);

    @Insert(
            """
            INSERT INTO worker_heartbeats
                (deployment_scope, worker_id, capability_hash, available_slots, heartbeat_at, expires_at, max_archive_bytes, max_file_bytes)
            VALUES (#{deployment}, #{worker}, #{capability}, #{slots}, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + CAST(#{ttl} AS BIGINT) * INTERVAL '1' SECOND, #{archiveBytes}, #{fileBytes})
            """)
    int register(
            @Param("deployment") String deployment,
            @Param("worker") UUID worker,
            @Param("capability") String capability,
            @Param("slots") int slots,
            @Param("ttl") long ttl,
            @Param("archiveBytes") long archiveBytes,
            @Param("fileBytes") long fileBytes);

    @Update(
            """
            UPDATE worker_heartbeats SET available_slots = 0, expires_at = CURRENT_TIMESTAMP
            WHERE deployment_scope = #{deployment} AND worker_id = #{worker}
            """)
    int retire(@Param("deployment") String deployment, @Param("worker") UUID worker);

    @Select(
            """
            SELECT COUNT(*) AS registered,
                COALESCE(SUM(CASE WHEN expires_at > CURRENT_TIMESTAMP THEN 1 ELSE 0 END), 0) AS healthy,
                COALESCE(SUM(CASE WHEN expires_at > CURRENT_TIMESTAMP THEN available_slots ELSE 0 END), 0)
                    AS available_slots
            FROM worker_heartbeats WHERE deployment_scope = #{deployment} AND capability_hash = #{capability}
                AND max_archive_bytes >= #{archiveBytes} AND max_file_bytes >= #{fileBytes}
            """)
    Presence presence(
            @Param("deployment") String deployment,
            @Param("capability") String capability,
            @Param("archiveBytes") long archiveBytes,
            @Param("fileBytes") long fileBytes);
}
