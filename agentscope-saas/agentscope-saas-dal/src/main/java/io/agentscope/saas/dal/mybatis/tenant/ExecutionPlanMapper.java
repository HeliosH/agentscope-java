/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.Approval;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewApproval;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewTaskEdge;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.PlanTask;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.PlanTaskLink;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.StoredPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.TaskEdge;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant MyBatis mapper for structured plans, DAG edges, and approval decisions. */
public interface ExecutionPlanMapper {

    @Select(
            """
            SELECT id, org_id, run_id, version, status, goal, plan_json, plan_hash,
                   supersedes_plan_id, approval_required, created_at, decided_at
              FROM execution_plans
             WHERE run_id = #{runId} AND org_id = #{orgId}
             ORDER BY version DESC
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "version", javaType = int.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "goal", javaType = String.class),
        @Arg(column = "plan_json", javaType = String.class),
        @Arg(column = "plan_hash", javaType = String.class),
        @Arg(column = "supersedes_plan_id", javaType = UUID.class),
        @Arg(column = "approval_required", javaType = boolean.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_at", javaType = OffsetDateTime.class)
    })
    List<StoredPlan> findLatest(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, org_id, run_id, version, status, goal, plan_json, plan_hash,
                   supersedes_plan_id, approval_required, created_at, decided_at
              FROM execution_plans
             WHERE run_id = #{runId} AND org_id = #{orgId} AND plan_hash = #{planHash}
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "version", javaType = int.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "goal", javaType = String.class),
        @Arg(column = "plan_json", javaType = String.class),
        @Arg(column = "plan_hash", javaType = String.class),
        @Arg(column = "supersedes_plan_id", javaType = UUID.class),
        @Arg(column = "approval_required", javaType = boolean.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_at", javaType = OffsetDateTime.class)
    })
    List<StoredPlan> findByHash(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("planHash") String planHash);

    @Select(
            """
            SELECT id, org_id, run_id, version, status, goal, plan_json, plan_hash,
                   supersedes_plan_id, approval_required, created_at, decided_at
              FROM execution_plans
             WHERE id = #{planId} AND run_id = #{runId} AND org_id = #{orgId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "run_id", javaType = UUID.class),
        @Arg(column = "version", javaType = int.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "goal", javaType = String.class),
        @Arg(column = "plan_json", javaType = String.class),
        @Arg(column = "plan_hash", javaType = String.class),
        @Arg(column = "supersedes_plan_id", javaType = UUID.class),
        @Arg(column = "approval_required", javaType = boolean.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_at", javaType = OffsetDateTime.class)
    })
    List<StoredPlan> findById(
            @Param("planId") UUID planId, @Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO execution_plans
                (id, org_id, run_id, version, status, goal, plan_json, plan_hash,
                 supersedes_plan_id, approval_required, created_by, created_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{version}, #{status}, #{goal},
                 #{planJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{planHash}, #{supersedesPlanId}, #{approvalRequired}, #{createdBy}, #{createdAt})
            """)
    int insertPlan(NewPlan plan);

    @Insert(
            """
            INSERT INTO execution_plan_tasks
                (id, org_id, run_id, plan_id, task_id, client_task_id, task_spec_hash)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{planId}, #{taskId}, #{clientTaskId},
                 #{taskSpecHash})
            """)
    int insertPlanTask(PlanTaskLink link);

    @Insert(
            """
            INSERT INTO task_edges
                (id, org_id, run_id, plan_id, from_task_id, to_task_id, edge_type)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{planId}, #{fromTaskId}, #{toTaskId}, #{edgeType})
            """)
    int insertEdge(NewTaskEdge edge);

    @Insert(
            """
            INSERT INTO run_approvals
                (id, org_id, run_id, plan_id, approval_type, status, request_json, requested_at)
            VALUES
                (#{id}, #{orgId}, #{runId}, #{planId}, #{approvalType}, #{status},
                 #{requestJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                 #{requestedAt})
            """)
    int insertApproval(NewApproval approval);

    @Select(
            """
            SELECT id, plan_id, status, request_json, decision_json, idempotency_key,
                   requested_at, decided_at, decided_by
              FROM run_approvals
             WHERE run_id = #{runId} AND plan_id = #{planId} AND org_id = #{orgId}
               AND status = 'PENDING'
             ORDER BY requested_at DESC
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "plan_id", javaType = UUID.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "request_json", javaType = String.class),
        @Arg(column = "decision_json", javaType = String.class),
        @Arg(column = "idempotency_key", javaType = String.class),
        @Arg(column = "requested_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_by", javaType = UUID.class)
    })
    List<Approval> findPendingApproval(
            @Param("runId") UUID runId, @Param("planId") UUID planId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT id, plan_id, status, request_json, decision_json, idempotency_key,
                   requested_at, decided_at, decided_by
              FROM run_approvals
             WHERE run_id = #{runId} AND org_id = #{orgId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "plan_id", javaType = UUID.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "request_json", javaType = String.class),
        @Arg(column = "decision_json", javaType = String.class),
        @Arg(column = "idempotency_key", javaType = String.class),
        @Arg(column = "requested_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_at", javaType = OffsetDateTime.class),
        @Arg(column = "decided_by", javaType = UUID.class)
    })
    List<Approval> findApprovalByIdempotencyKey(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("idempotencyKey") String idempotencyKey);

    @Update(
            """
            UPDATE run_approvals
               SET status = #{status},
                   decision_json =
                       #{decisionJson,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler},
                   decided_by = #{decidedBy}, idempotency_key = #{idempotencyKey},
                   decided_at = #{decidedAt}
             WHERE id = #{approvalId} AND org_id = #{orgId} AND status = 'PENDING'
            """)
    int decideApproval(
            @Param("approvalId") UUID approvalId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("decisionJson") String decisionJson,
            @Param("decidedBy") UUID decidedBy,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("decidedAt") OffsetDateTime decidedAt);

    @Update(
            """
            UPDATE execution_plans
               SET status = #{status}, decided_at = #{decidedAt}
             WHERE id = #{planId} AND org_id = #{orgId}
            """)
    int updatePlanStatus(
            @Param("planId") UUID planId,
            @Param("orgId") UUID orgId,
            @Param("status") String status,
            @Param("decidedAt") OffsetDateTime decidedAt);

    @Update(
            """
            UPDATE execution_plans
               SET status = 'SUPERSEDED', decided_at = COALESCE(decided_at, #{updatedAt})
             WHERE id = #{planId} AND org_id = #{orgId}
               AND status <> 'SUPERSEDED'
            """)
    int supersedePlan(
            @Param("planId") UUID planId,
            @Param("orgId") UUID orgId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE assistant_runs
               SET mode = #{mode}, status = #{status}, completed_at = NULL,
                   updated_at = #{updatedAt}, version = version + 1
             WHERE id = #{runId} AND org_id = #{orgId}
               AND status NOT IN ('CANCELLED', 'SUCCEEDED')
            """)
    int updateRunPlanningState(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("mode") String mode,
            @Param("status") String status,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'SUCCEEDED', completed_at = #{completedAt},
                   updated_at = #{completedAt}, version = version + 1
             WHERE run_id = #{runId} AND org_id = #{orgId} AND parent_id IS NULL
               AND status NOT IN ('SUCCEEDED', 'CANCELLED', 'FAILED')
            """)
    int settleCoordinatorTask(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE run_attempts
               SET status = 'SUCCEEDED', completed_at = #{completedAt},
                   lease_expires_at = NULL, updated_at = #{completedAt}
             WHERE run_id = #{runId} AND org_id = #{orgId}
               AND task_id IN (
                   SELECT id FROM task_nodes
                    WHERE run_id = #{runId} AND org_id = #{orgId} AND parent_id IS NULL
               )
               AND status NOT IN ('SUCCEEDED', 'CANCELLED', 'FAILED')
            """)
    int settleCoordinatorAttempts(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE agent_runs
               SET status = 'SUCCEEDED', completed_at = #{completedAt},
                   updated_at = #{completedAt}, version = version + 1
             WHERE run_id = #{runId} AND org_id = #{orgId}
               AND task_id IN (
                   SELECT id FROM task_nodes
                    WHERE run_id = #{runId} AND org_id = #{orgId} AND parent_id IS NULL
               )
               AND status NOT IN ('SUCCEEDED', 'CANCELLED', 'FAILED')
            """)
    int settleCoordinatorAgents(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE task_nodes
               SET status = 'CANCELLED', completed_at = #{completedAt},
                   updated_at = #{completedAt}, version = version + 1
             WHERE run_id = #{runId} AND org_id = #{orgId}
               AND status IN ('PENDING', 'READY')
               AND id IN (
                   SELECT task_id FROM execution_plan_tasks
                    WHERE run_id = #{runId} AND org_id = #{orgId}
                      AND plan_id <> #{retainedPlanId}
               )
            """)
    int cancelSupersededPlanTasks(
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("retainedPlanId") UUID retainedPlanId,
            @Param("completedAt") OffsetDateTime completedAt);

    @Update(
            """
            UPDATE task_nodes target
               SET status = 'READY', updated_at = #{updatedAt}, version = version + 1
             WHERE target.run_id = #{runId} AND target.org_id = #{orgId}
               AND target.status = 'PENDING'
               AND target.id IN (
                   SELECT task_id FROM execution_plan_tasks
                    WHERE plan_id = #{planId} AND run_id = #{runId} AND org_id = #{orgId}
               )
               AND NOT EXISTS (
                   SELECT 1 FROM task_edges edge
                    WHERE edge.plan_id = #{planId} AND edge.to_task_id = target.id
               )
            """)
    int releaseRootPlanTasks(
            @Param("planId") UUID planId,
            @Param("runId") UUID runId,
            @Param("orgId") UUID orgId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    @Select(
            """
            SELECT task.id AS task_id, link.client_task_id, task.title,
                   COALESCE(agent.agent_type, 'assistant') AS agent_type, task.status,
                   task.workspace_mode, task.acceptance_json, task.owner_agent_run_id,
                   link.task_spec_hash
              FROM execution_plan_tasks link
              JOIN task_nodes task ON task.id = link.task_id
              LEFT JOIN agent_runs agent ON agent.id = task.owner_agent_run_id
             WHERE link.plan_id = #{planId} AND link.run_id = #{runId}
               AND link.org_id = #{orgId}
             ORDER BY link.client_task_id
            """)
    @ConstructorArgs({
        @Arg(column = "task_id", javaType = UUID.class),
        @Arg(column = "client_task_id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "workspace_mode", javaType = String.class),
        @Arg(column = "acceptance_json", javaType = String.class),
        @Arg(column = "owner_agent_run_id", javaType = UUID.class),
        @Arg(column = "task_spec_hash", javaType = String.class)
    })
    List<PlanTask> findPlanTasks(
            @Param("planId") UUID planId, @Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Select(
            """
            SELECT from_task_id, to_task_id, edge_type
              FROM task_edges
             WHERE plan_id = #{planId} AND run_id = #{runId} AND org_id = #{orgId}
             ORDER BY created_at
            """)
    @ConstructorArgs({
        @Arg(column = "from_task_id", javaType = UUID.class),
        @Arg(column = "to_task_id", javaType = UUID.class),
        @Arg(column = "edge_type", javaType = String.class)
    })
    List<TaskEdge> findPlanEdges(
            @Param("planId") UUID planId, @Param("runId") UUID runId, @Param("orgId") UUID orgId);
}
