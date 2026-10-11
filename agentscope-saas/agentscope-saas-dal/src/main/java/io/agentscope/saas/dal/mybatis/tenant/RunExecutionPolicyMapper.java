/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.RunExecutionPolicy;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RunExecutionPolicyMapper {
    @Insert(
            """
            INSERT INTO run_execution_policies
              (org_id, run_id, version, deployment_scope, capability_hash, max_files,
               max_file_bytes, max_workspace_bytes, recovery_enabled, recovery_mode,
               recovery_max_attempts, recovery_initial_delay_millis, recovery_max_delay_millis, accepted_at)
            SELECT #{orgId}, #{runId}, #{version}, #{deploymentScope}, #{capabilityHash}, #{maxFiles},
                   #{maxFileBytes}, #{maxWorkspaceBytes}, #{recoveryEnabled}, #{recoveryMode},
                   #{recoveryMaxAttempts}, #{recoveryInitialDelayMillis}, #{recoveryMaxDelayMillis}, #{acceptedAt}
              FROM assistant_runs WHERE id = #{runId} AND org_id = #{orgId}
            """)
    int insert(RunExecutionPolicy policy);

    @Update(
            """
            UPDATE assistant_runs SET execution_policy_version = #{version}
              WHERE org_id = #{orgId} AND id = #{runId} AND execution_policy_version IS NULL
            """)
    int markAccepted(RunExecutionPolicy policy);

    @Select(
            "SELECT execution_policy_version FROM assistant_runs WHERE org_id = #{orgId} AND id ="
                    + " #{runId} AND execution_policy_version IS NOT NULL")
    List<Integer> requiredVersion(@Param("orgId") UUID orgId, @Param("runId") UUID runId);

    @Select(
            """
            SELECT org_id, run_id, version, deployment_scope, capability_hash, max_files,
                   max_file_bytes, max_workspace_bytes, recovery_enabled, recovery_mode,
                   recovery_max_attempts, recovery_initial_delay_millis, recovery_max_delay_millis, accepted_at
              FROM run_execution_policies WHERE org_id = #{orgId} AND run_id = #{runId}
            """)
    List<RunExecutionPolicy> find(@Param("orgId") UUID orgId, @Param("runId") UUID runId);
}
