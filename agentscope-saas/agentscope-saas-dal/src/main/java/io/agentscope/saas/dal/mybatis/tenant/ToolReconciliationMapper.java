/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Operation;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.ReplayPermit;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Resolution;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.RunScope;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ToolReconciliationMapper {
    String OP_COLUMNS =
            "o.id,o.org_id,o.run_id,r.user_id,r.agent_id,o.task_id,o.agent_run_id,o.attempt_id,o.operation_key,o.tool_call_id,o.tool_name,o.input_hash,o.retry_safety,o.status,o.version,r.version"
                + " AS run_version,o.prepared_at,o.error_type,o.error_message,o.started_at,o.completed_at,o.tool_contract_hash,o.tool_contract_recoverable,o.logical_origin_id";
    String RES_COLUMNS =
            "id,org_id,run_id,operation_id,sequence_no AS"
                + " sequence,operation_version,actor_id,command_key,command_hash,decision,reason,evidence_ref,CAST(result_json"
                + " AS VARCHAR) AS result_json,CAST(source_fact_json AS VARCHAR) AS"
                + " source_fact_json,replay_approved,created_at";

    @Select(
            """
            SELECT org_id,id AS run_id,user_id,agent_id,session_id,session_generation,
                   version AS run_version,status,cancel_requested FROM assistant_runs WHERE org_id = #{org} AND id = #{run}
            """)
    RunScope run(@Param("org") UUID org, @Param("run") UUID run);

    @Select(
            "SELECT "
                    + OP_COLUMNS
                    + " FROM tool_operations o JOIN assistant_runs r ON r.id = o.run_id AND"
                    + " r.org_id = o.org_id WHERE o.org_id = #{org} AND o.run_id = #{run} AND o.id"
                    + " = #{operation} FOR UPDATE")
    Operation lockOperation(
            @Param("org") UUID org, @Param("run") UUID run, @Param("operation") UUID operation);

    @Select(
            "SELECT "
                    + OP_COLUMNS
                    + " FROM tool_operations o JOIN assistant_runs r ON r.id = o.run_id AND"
                    + " r.org_id = o.org_id WHERE o.org_id = #{org} AND o.run_id = #{run} AND"
                    + " (CAST(#{after} AS VARCHAR) IS NULL OR o.id > #{after}) ORDER BY o.id LIMIT"
                    + " #{limit}")
    List<Operation> operations(
            @Param("org") UUID org,
            @Param("run") UUID run,
            @Param("after") UUID after,
            @Param("limit") int limit);

    @Select(
            "SELECT "
                    + OP_COLUMNS
                    + " FROM tool_operations o JOIN assistant_runs r ON r.id = o.run_id AND"
                    + " r.org_id = o.org_id WHERE o.org_id = #{org} AND (#{state} = 'ALL' OR"
                    + " o.status = #{state}) AND (CAST(#{after} AS VARCHAR) IS NULL OR o.id >"
                    + " #{after}) ORDER BY o.id LIMIT #{limit}")
    List<Operation> organizationOperations(
            @Param("org") UUID org,
            @Param("state") String state,
            @Param("after") UUID after,
            @Param("limit") int limit);

    @Select(
            "SELECT "
                    + RES_COLUMNS
                    + " FROM tool_operation_resolutions WHERE org_id = #{org} AND actor_id ="
                    + " #{actor} AND command_key = #{key}")
    Resolution command(
            @Param("org") UUID org, @Param("actor") UUID actor, @Param("key") String key);

    @Select(
            "SELECT "
                    + RES_COLUMNS
                    + " FROM tool_operation_resolutions WHERE org_id = #{org} AND run_id = #{run}"
                    + " AND operation_id = #{operation} ORDER BY sequence_no DESC LIMIT 1")
    Resolution latest(
            @Param("org") UUID org, @Param("run") UUID run, @Param("operation") UUID operation);

    @Select(
            "SELECT "
                    + RES_COLUMNS
                    + " FROM tool_operation_resolutions WHERE org_id = #{org} AND run_id = #{run}"
                    + " AND operation_id = #{operation} AND sequence_no > #{after} ORDER BY"
                    + " sequence_no LIMIT #{limit}")
    List<Resolution> history(
            @Param("org") UUID org,
            @Param("run") UUID run,
            @Param("operation") UUID operation,
            @Param("after") long after,
            @Param("limit") int limit);

    @Insert(
            """
            INSERT INTO tool_operation_resolutions(id,org_id,run_id,operation_id,sequence_no,operation_version,
                actor_id,command_key,command_hash,decision,reason,evidence_ref,result_json,source_fact_json,replay_approved,created_at)
            VALUES(#{id},#{orgId},#{runId},#{operationId},#{sequence},#{operationVersion},#{actorId},#{commandKey},
                #{commandHash},#{decision},#{reason},#{evidenceRef},CAST(#{resultJson} AS JSON),CAST(#{sourceFactJson} AS JSON),#{replayApproved},#{createdAt})
            """)
    int append(Resolution resolution);

    @Insert(
            """
            INSERT INTO tool_operation_replay_permits(id,org_id,run_id,operation_id,resolution_id,operation_version,
                actor_id,tool_name,tool_call_id,input_hash,valid_until)
            VALUES(#{id},#{orgId},#{runId},#{operationId},#{resolutionId},#{operationVersion},#{actorId},#{toolName},#{toolCallId},#{inputHash},#{validUntil})
            """)
    int approve(ReplayPermit permit);
}
