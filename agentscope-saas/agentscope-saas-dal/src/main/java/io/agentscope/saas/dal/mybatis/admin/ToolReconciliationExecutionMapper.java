/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.dal.mybatis.tenant.ToolReconciliationMapper;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Resolution;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Internal execution path; the journal locks and validates the operation before consulting it. */
public interface ToolReconciliationExecutionMapper {
    @Select(
            "SELECT "
                    + ToolReconciliationMapper.RES_COLUMNS
                    + " FROM tool_operation_resolutions WHERE org_id = #{org} AND run_id = #{run}"
                    + " AND operation_id = #{operation} ORDER BY sequence_no DESC LIMIT 1")
    Resolution latest(
            @Param("org") UUID org, @Param("run") UUID run, @Param("operation") UUID operation);

    @Select(
            """
            SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM tool_operation_replay_permits p
                WHERE p.org_id = #{org} AND p.run_id = #{run} AND p.operation_id = #{operation}
                  AND p.operation_version = #{version} - 1 AND p.consumed_at IS NOT NULL) THEN 'NOT_REQUIRED'
              WHEN EXISTS (SELECT 1 FROM tool_operation_replay_permits p
                JOIN tool_operation_resolutions r ON r.id = p.resolution_id
                JOIN users u ON u.id = p.actor_id AND u.org_id = p.org_id
                WHERE p.org_id = #{org} AND p.run_id = #{run} AND p.operation_id = #{operation}
                  AND p.operation_version = #{version} - 1 AND p.consumed_at IS NOT NULL
                  AND p.invocation_id = #{invocation} AND p.valid_until > #{now}
                  AND p.tool_name = #{toolName} AND p.tool_call_id = #{toolCall} AND p.input_hash = #{inputHash}
                  AND u.role IN ('admin','platform_admin') AND r.decision = 'CONFIRMED_NOT_PERFORMED' AND r.replay_approved
                  AND NOT EXISTS (SELECT 1 FROM tool_operation_resolutions newer WHERE newer.org_id = #{org}
                    AND newer.operation_id = #{operation} AND newer.sequence_no > r.sequence_no)) THEN 'VALID'
              ELSE 'INVALID' END
            """)
    String claimAuthority(
            @Param("org") UUID org,
            @Param("run") UUID run,
            @Param("operation") UUID operation,
            @Param("version") long version,
            @Param("invocation") UUID invocation,
            @Param("toolName") String toolName,
            @Param("toolCall") String toolCall,
            @Param("inputHash") String inputHash,
            @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE tool_operation_replay_permits SET invocation_id = #{invocation},consumed_at = #{now}
             WHERE org_id = #{org} AND run_id = #{run} AND operation_id = #{operation}
               AND operation_version = #{version} AND resolution_id = #{resolution}
               AND tool_name = #{toolName} AND tool_call_id = #{toolCall} AND input_hash = #{inputHash}
               AND consumed_at IS NULL AND valid_until > #{now}
               AND EXISTS (SELECT 1 FROM users u WHERE u.id = tool_operation_replay_permits.actor_id
                    AND u.org_id = tool_operation_replay_permits.org_id AND u.role IN ('admin','platform_admin'))
               AND EXISTS (SELECT 1 FROM tool_operation_resolutions r WHERE r.id = #{resolution}
                    AND r.org_id = #{org} AND r.run_id = #{run} AND r.operation_id = #{operation}
                    AND r.operation_version = #{version} AND r.decision = 'CONFIRMED_NOT_PERFORMED' AND r.replay_approved)
               AND NOT EXISTS (SELECT 1 FROM tool_operation_resolutions newer WHERE newer.org_id = #{org}
                    AND newer.operation_id = #{operation} AND newer.sequence_no >
                    (SELECT sequence_no FROM tool_operation_resolutions WHERE id = #{resolution}))
            """)
    int consume(
            @Param("org") UUID org,
            @Param("run") UUID run,
            @Param("operation") UUID operation,
            @Param("version") long version,
            @Param("resolution") UUID resolution,
            @Param("invocation") UUID invocation,
            @Param("toolName") String toolName,
            @Param("toolCall") String toolCall,
            @Param("inputHash") String inputHash,
            @Param("now") OffsetDateTime now);
}
