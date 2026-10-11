/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository.RecoveryQueue;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RecoveryQueueMapper {
    @Select(
            """
            SELECT r.status AS run_status, r.cancel_requested, t.status AS task_status,
                t.recovery_phase AS phase, t.recovery_reason AS reason, t.recovery_count,
                (SELECT COUNT(*) FROM run_attempts a WHERE a.org_id = r.org_id AND a.task_id = t.id)
                    AS attempt_count,
                t.next_recovery_at, t.updated_at AS queued_at
            FROM assistant_runs r JOIN task_nodes t ON t.org_id = r.org_id AND t.run_id = r.id
            WHERE r.org_id = #{org} AND r.user_id = #{user} AND r.agent_id = #{agent} AND r.id = #{run}
                AND t.recovery_count > 0
            ORDER BY CASE WHEN t.status = 'READY' THEN 0 ELSE 1 END, t.updated_at DESC, t.id
            LIMIT 1
            """)
    List<RecoveryQueue> ownedRecovery(
            @Param("org") UUID org,
            @Param("user") UUID user,
            @Param("agent") UUID agent,
            @Param("run") UUID run);
}
