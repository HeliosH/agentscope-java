/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.InvocationReference;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.NewInvocation;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.PurposePolicy;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Settlement;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Totals;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** All privileged operations retain explicit tenant and ownership predicates. */
public interface ModelInvocationMapper {
    @Select(
            "SELECT id FROM orgs WHERE id = #{orgId} AND (#{requireActive} = FALSE OR status ="
                    + " 'active') FOR UPDATE")
    List<UUID> lockOrg(@Param("orgId") UUID orgId, @Param("requireActive") boolean requireActive);

    @Select("SELECT id FROM orgs WHERE id = #{orgId} AND status = 'active'")
    List<UUID> activeOrganization(@Param("orgId") UUID orgId);

    @Select("SELECT id FROM users WHERE id = #{userId} AND org_id = #{orgId} FOR UPDATE")
    List<UUID> lockUser(Scope scope);

    @Select(
            """
            <script>SELECT t.id FROM task_nodes t
            JOIN assistant_runs r ON r.id = t.run_id AND r.org_id = t.org_id
            <if test="scope.agentRunId != null">JOIN agent_runs ar ON ar.task_id = t.id AND ar.org_id = t.org_id AND ar.run_id = t.run_id</if>
            WHERE t.id = #{scope.taskId} AND t.org_id = #{scope.orgId}
              AND t.run_id = #{scope.runId} AND r.user_id = #{scope.userId}
              AND t.status = 'RUNNING' AND r.status = 'RUNNING'
              <if test="scope.agentRunId != null">AND ar.id = #{scope.agentRunId} AND ar.status = 'RUNNING'</if>
              <if test="scope.attemptId != null">AND EXISTS (
                SELECT 1 FROM run_attempts a
                WHERE a.id = #{scope.attemptId} AND a.org_id = t.org_id AND a.run_id = t.run_id
                  AND a.task_id = t.id AND a.status = 'RUNNING'
                  <choose><when test="scope.agentRunId != null">AND a.agent_run_id = ar.id</when>
                  <otherwise>AND a.agent_run_id IS NULL</otherwise></choose>
                  AND ((a.lease_owner = #{scope.leaseOwner} AND a.lease_expires_at &gt;= #{now})
                    OR (a.lease_owner IS NULL AND a.lease_expires_at IS NULL
                        AND a.idempotency_key = #{scope.leaseOwner})))</if></script>
            """)
    List<UUID> activeLease(@Param("scope") Scope scope, @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT
                   COALESCE((SELECT SUM(metric_value) FROM usage_records WHERE org_id = #{scope.orgId} AND (#{userOnly} = FALSE OR user_id = #{scope.userId})
                       AND recorded_at >= #{since} AND metric = 'tokens_total'),0)
                   + COALESCE((SELECT SUM(CASE WHEN deadline_at > #{now} THEN reserved_tokens
                           ELSE estimated_input_tokens END) FROM model_invocations WHERE org_id = #{scope.orgId} AND (#{userOnly} = FALSE OR user_id = #{scope.userId})
                       AND started_at >= #{since} AND status = 'STARTED'),0) AS tokens,
                   COALESCE((SELECT SUM(metric_value) FROM usage_records WHERE org_id = #{scope.orgId} AND (#{userOnly} = FALSE OR user_id = #{scope.userId})
                       AND recorded_at >= #{since} AND metric = 'cost_micros'),0)
                   + COALESCE((SELECT SUM(reserved_cost_micros) FROM model_invocations WHERE org_id = #{scope.orgId} AND (#{userOnly} = FALSE OR user_id = #{scope.userId})
                       AND started_at >= #{since} AND status = 'STARTED' AND deadline_at > #{now}),0) AS cost_micros,
                   COALESCE((SELECT SUM(metric_value) FROM usage_records WHERE org_id = #{scope.orgId} AND (#{userOnly} = FALSE OR user_id = #{scope.userId})
                       AND recorded_at >= #{since} AND metric = 'model_calls'),0) AS calls
            """)
    @ConstructorArgs({
        @Arg(column = "tokens", javaType = long.class),
        @Arg(column = "cost_micros", javaType = long.class),
        @Arg(column = "calls", javaType = long.class)
    })
    Totals dailyUsage(
            @Param("scope") Scope scope,
            @Param("userOnly") boolean userOnly,
            @Param("since") OffsetDateTime since,
            @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT COALESCE(SUM(reserved_tokens),0) AS tokens,
                   COALESCE(SUM(reserved_cost_micros),0) AS cost_micros, COUNT(*) AS calls
            FROM model_invocations WHERE org_id = #{scope.orgId} AND run_id = #{scope.runId}
              AND (#{taskOnly} = FALSE OR task_id = #{scope.taskId})
              AND status = 'STARTED' AND deadline_at > #{now}
            """)
    @ConstructorArgs({
        @Arg(column = "tokens", javaType = long.class),
        @Arg(column = "cost_micros", javaType = long.class),
        @Arg(column = "calls", javaType = long.class)
    })
    Totals runReservations(
            @Param("scope") Scope scope,
            @Param("taskOnly") boolean taskOnly,
            @Param("now") OffsetDateTime now);

    @Insert(
            """
            INSERT INTO model_invocations (id, org_id, user_id, run_id, task_id, agent_run_id,
                attempt_id, lease_owner, purpose, model_id, route_version, reserved_tokens,
                reserved_cost_micros, estimated_input_tokens, deadline_at, started_at)
            VALUES (#{id}, #{scope.orgId}, #{scope.userId}, #{scope.runId}, #{scope.taskId},
                #{scope.agentRunId}, #{scope.attemptId}, #{scope.leaseOwner}, #{purpose},
                #{modelId}, #{routeVersion}, #{reservedTokens}, #{reservedCostMicros},
                #{estimatedInputTokens}, #{deadlineAt}, #{startedAt})
            """)
    int insert(NewInvocation invocation);

    @Select(
            """
            SELECT id, org_id, user_id, run_id, task_id, agent_run_id, attempt_id, lease_owner, purpose, model_id, route_version, status, reserved_tokens, reserved_cost_micros, estimated_input_tokens, input_tokens, output_tokens, total_tokens, usage_source, deadline_at, started_at, finished_at, reason_code FROM model_invocations
            WHERE org_id = #{orgId} AND id = #{id}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "agent_run_id", javaType = UUID.class),
        @Arg(column = "attempt_id", javaType = UUID.class),
        @Arg(column = "lease_owner", javaType = String.class),
        @Arg(column = "purpose", javaType = String.class),
        @Arg(column = "model_id", javaType = String.class),
        @Arg(column = "route_version", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "reserved_tokens", javaType = long.class),
        @Arg(column = "reserved_cost_micros", javaType = long.class),
        @Arg(column = "estimated_input_tokens", javaType = long.class),
        @Arg(column = "input_tokens", javaType = long.class),
        @Arg(column = "output_tokens", javaType = long.class),
        @Arg(column = "total_tokens", javaType = long.class),
        @Arg(column = "usage_source", javaType = String.class),
        @Arg(column = "deadline_at", javaType = OffsetDateTime.class),
        @Arg(column = "started_at", javaType = OffsetDateTime.class),
        @Arg(column = "finished_at", javaType = OffsetDateTime.class),
        @Arg(column = "reason_code", javaType = String.class)
    })
    List<ModelInvocationData> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Select(
            "SELECT org_id, id FROM model_invocations WHERE status = 'STARTED' AND deadline_at <"
                    + " #{before} ORDER BY deadline_at, id LIMIT #{limit}")
    @ConstructorArgs({
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "id", javaType = UUID.class)
    })
    List<InvocationReference> findExpired(
            @Param("before") OffsetDateTime before, @Param("limit") int limit);

    @Update(
            """
            UPDATE model_invocations SET status = #{status}, input_tokens = #{inputTokens},
                output_tokens = #{outputTokens}, total_tokens = #{totalTokens},
                cost_micros = #{costMicros}, usage_source = #{usageSource},
                reason_code = #{reasonCode}, finished_at = #{finishedAt}
            WHERE org_id = #{orgId} AND id = #{id} AND status = 'STARTED'
            """)
    int settle(Settlement settlement);

    @Insert(
            """
            INSERT INTO usage_records (org_id, user_id, metric, metric_value, model, recorded_at)
            VALUES (#{scope.orgId}, #{scope.userId}, #{metric}, #{value}, #{modelId}, CURRENT_TIMESTAMP)
            """)
    int recordMetric(
            @Param("scope") Scope scope,
            @Param("metric") String metric,
            @Param("value") long value,
            @Param("modelId") String modelId);

    @Select(
            """
            SELECT org_id, purpose, model_id, max_input_tokens, max_output_tokens, timeout_seconds, version
            FROM model_invocation_policies WHERE org_id = #{orgId} AND purpose = #{purpose}
            """)
    @ConstructorArgs({
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "purpose", javaType = String.class),
        @Arg(column = "model_id", javaType = String.class),
        @Arg(column = "max_input_tokens", javaType = int.class),
        @Arg(column = "max_output_tokens", javaType = int.class),
        @Arg(column = "timeout_seconds", javaType = int.class),
        @Arg(column = "version", javaType = String.class)
    })
    List<PurposePolicy> policy(@Param("orgId") UUID orgId, @Param("purpose") String purpose);

    @Update(
            """
            UPDATE model_invocation_policies SET model_id = #{modelId}, max_input_tokens = #{maxInputTokens},
                max_output_tokens = #{maxOutputTokens}, timeout_seconds = #{timeoutSeconds}, version = #{version}
            WHERE org_id = #{orgId} AND purpose = #{purpose}
            """)
    int updatePolicy(PurposePolicy policy);

    @Insert(
            """
            INSERT INTO model_invocation_policies (org_id, purpose, model_id, max_input_tokens,
                max_output_tokens, timeout_seconds, version)
            VALUES (#{orgId}, #{purpose}, #{modelId}, #{maxInputTokens}, #{maxOutputTokens}, #{timeoutSeconds}, #{version})
            """)
    int insertPolicy(PurposePolicy policy);
}
