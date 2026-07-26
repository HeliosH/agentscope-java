/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.ResourceBudget;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.TaskSpec;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.Approval;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewApproval;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.NewTaskEdge;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.PlanTask;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.PlanTaskLink;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.StoredPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlanRepository.TaskEdge;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AssistantRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewTask;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.TaskNode;
import io.agentscope.saas.orchestration.ExecutionPlanIntegrity.CanonicalPayload;
import io.agentscope.saas.orchestration.ExecutionPlanValidator.ValidatedPlan;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application/domain service for plan publication, revision, approval, and DAG activation. */
@Service
public class ExecutionPlanService {

    public static final String RUN_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String RUN_WAITING_PLAN = "WAITING_PLAN";
    public static final String PLAN_PROPOSED = "PROPOSED";
    public static final String PLAN_APPROVED = "APPROVED";
    public static final String PLAN_REJECTED = "REJECTED";

    private final RunOrchestrationRepository runs;
    private final ExecutionPlanRepository plans;
    private final ExecutionPlanValidator validator;

    public ExecutionPlanService(
            RunOrchestrationRepository runs,
            ExecutionPlanRepository plans,
            ExecutionPlanValidator validator) {
        this.runs = runs;
        this.plans = plans;
        this.validator = validator;
    }

    /** Implements the durable behavior behind the model-facing {@code plan_publish} command. */
    @Transactional
    public PlanView publish(
            TenantContext tenant, UUID agentId, UUID runId, ExecutionPlan executionPlan) {
        Owned owned = lockOwned(tenant, agentId, runId);
        ValidatedPlan validated = validator.validate(executionPlan);
        CanonicalPayload canonical = ExecutionPlanIntegrity.canonicalize(validated.plan());
        Optional<StoredPlan> duplicate =
                plans.findByHash(runId, owned.run().orgId(), canonical.hash());
        if (duplicate.isPresent()) {
            return view(duplicate.get(), owned.run().orgId(), true);
        }

        StoredPlan previous = plans.findLatest(runId, owned.run().orgId()).orElse(null);
        int version = previous != null ? previous.version() + 1 : 1;
        UUID planId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String initialStatus = executionPlan.approvalRequired() ? PLAN_PROPOSED : PLAN_APPROVED;
        plans.insertPlan(
                new NewPlan(
                        planId,
                        owned.run().orgId(),
                        runId,
                        version,
                        initialStatus,
                        executionPlan.goal(),
                        canonical.json(),
                        canonical.hash(),
                        previous != null ? previous.id() : null,
                        executionPlan.approvalRequired(),
                        owned.run().userId(),
                        now));

        Map<String, PlanTask> reusable =
                previous != null
                        ? reusableTasks(
                                plans.findPlanTasks(
                                        previous.id(), owned.run().id(), owned.run().orgId()))
                        : Map.of();
        Map<String, UUID> taskIds = new LinkedHashMap<>();
        for (TaskSpec specification : executionPlan.tasks()) {
            String taskSpecHash = ExecutionPlanIntegrity.canonicalize(specification).hash();
            PlanTask reused = reusable.get(specification.clientTaskId());
            UUID taskId;
            if (reused != null && taskSpecHash.equals(reused.taskSpecHash())) {
                taskId = reused.taskId();
            } else {
                taskId = createTask(owned, planId, version, specification, executionPlan, now);
            }
            taskIds.put(specification.clientTaskId(), taskId);
            plans.insertPlanTask(
                    new PlanTaskLink(
                            UUID.randomUUID(),
                            owned.run().orgId(),
                            runId,
                            planId,
                            taskId,
                            specification.clientTaskId(),
                            taskSpecHash));
        }
        for (TaskSpec specification : executionPlan.tasks()) {
            for (String dependency : specification.dependsOn()) {
                plans.insertEdge(
                        new NewTaskEdge(
                                UUID.randomUUID(),
                                owned.run().orgId(),
                                runId,
                                planId,
                                taskIds.get(dependency),
                                taskIds.get(specification.clientTaskId()),
                                "blocks"));
            }
        }

        plans.settleCoordinatorForPlan(runId, owned.run().orgId(), now);
        if (previous != null) {
            plans.supersedePlan(previous.id(), owned.run().orgId(), now);
            plans.cancelSupersededPlanTasks(runId, owned.run().orgId(), planId, now);
        }
        appendEvent(
                owned.run(),
                null,
                "PLAN_PROPOSED",
                json(
                        Map.of(
                                "planId",
                                planId.toString(),
                                "version",
                                version,
                                "taskCount",
                                executionPlan.tasks().size(),
                                "depth",
                                validated.depth(),
                                "maxParallelism",
                                validated.maxParallelism())));
        if (executionPlan.approvalRequired()) {
            plans.insertApproval(
                    new NewApproval(
                            UUID.randomUUID(),
                            owned.run().orgId(),
                            runId,
                            planId,
                            "EXECUTION_PLAN",
                            "PENDING",
                            json(
                                    Map.of(
                                            "planId",
                                            planId.toString(),
                                            "version",
                                            version,
                                            "goal",
                                            executionPlan.goal())),
                            now));
            requireUpdated(
                    plans.updateRunPlanningState(
                            runId, owned.run().orgId(), "PLANNED", RUN_WAITING_APPROVAL, now),
                    "move Run to approval");
            appendEvent(
                    owned.run(),
                    null,
                    "APPROVAL_REQUIRED",
                    json(Map.of("planId", planId.toString(), "approvalType", "EXECUTION_PLAN")));
        } else {
            activate(owned.run(), planId, now);
        }
        return view(
                plans.findById(planId, runId, owned.run().orgId())
                        .orElseThrow(() -> new IllegalStateException("Published plan not found")),
                owned.run().orgId(),
                false);
    }

    @Transactional
    public ApprovalResult decide(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            UUID planId,
            String decision,
            String reason,
            String idempotencyKey) {
        Owned owned = lockOwned(tenant, agentId, runId);
        String key = required(idempotencyKey, "idempotencyKey", 255);
        Optional<Approval> existing =
                plans.findApprovalByIdempotencyKey(runId, owned.run().orgId(), key);
        if (existing.isPresent()) {
            Approval approval = existing.get();
            if (!planId.equals(approval.planId())) {
                throw new IllegalStateException("Idempotency key belongs to another plan");
            }
            return new ApprovalResult(
                    planId,
                    approval.status(),
                    approval.decidedAt(),
                    true,
                    "RUNNING".equals(owned.run().status()));
        }
        StoredPlan plan =
                plans.findById(planId, runId, owned.run().orgId())
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        Approval approval =
                plans.findPendingApproval(runId, planId, owned.run().orgId())
                        .orElseThrow(
                                () -> new IllegalStateException("Plan has no pending approval"));
        String normalized = required(decision, "decision", 16).toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String approvalStatus = "APPROVE".equals(normalized) ? "APPROVED" : "REJECTED";
        requireUpdated(
                plans.decideApproval(
                        approval.id(),
                        owned.run().orgId(),
                        approvalStatus,
                        json(Map.of("decision", normalized, "reason", valueOrEmpty(reason))),
                        owned.run().userId(),
                        key,
                        now),
                "decide approval");
        requireUpdated(
                plans.updatePlanStatus(
                        plan.id(),
                        owned.run().orgId(),
                        "APPROVE".equals(normalized) ? PLAN_APPROVED : PLAN_REJECTED,
                        now),
                "update plan decision");
        if ("APPROVE".equals(normalized)) {
            activate(owned.run(), planId, now);
            appendEvent(
                    owned.run(),
                    null,
                    "PLAN_APPROVED",
                    json(Map.of("planId", planId.toString(), "version", plan.version())));
        } else {
            requireUpdated(
                    plans.updateRunPlanningState(
                            runId, owned.run().orgId(), "PLANNED", RUN_WAITING_PLAN, now),
                    "return Run to planning");
            appendEvent(
                    owned.run(),
                    null,
                    "PLAN_REJECTED",
                    json(
                            Map.of(
                                    "planId",
                                    planId.toString(),
                                    "version",
                                    plan.version(),
                                    "reason",
                                    valueOrEmpty(reason))));
        }
        return new ApprovalResult(planId, approvalStatus, now, false, "APPROVE".equals(normalized));
    }

    @Transactional(readOnly = true)
    public Optional<PlanView> latest(TenantContext tenant, UUID agentId, UUID runId) {
        Owned owned = findOwned(tenant, agentId, runId).orElse(null);
        if (owned == null) {
            return Optional.empty();
        }
        return plans.findLatest(runId, owned.run().orgId())
                .map(plan -> view(plan, owned.run().orgId(), false));
    }

    private UUID createTask(
            Owned owned,
            UUID planId,
            int version,
            TaskSpec specification,
            ExecutionPlan plan,
            OffsetDateTime now) {
        UUID taskId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        ResourceBudget taskBudget = specification.budget();
        runs.insertTask(
                new NewTask(
                        taskId,
                        owned.run().orgId(),
                        owned.run().id(),
                        owned.rootTask().id(),
                        "plan-v" + version + ":" + specification.clientTaskId(),
                        null,
                        specification.title(),
                        "agent",
                        "PENDING",
                        specification.priority(),
                        json(specification.input()),
                        json(specification.expectedOutputs()),
                        "{}",
                        json(specification.acceptanceCriteria()),
                        specification.workspaceMode().name(),
                        specification.maxAttempts(),
                        specification.retryMode().toUpperCase(Locale.ROOT),
                        2,
                        taskBudget.tokenLimit(),
                        taskBudget.costLimitMicros(),
                        taskBudget.modelCallLimit(),
                        deadline(now, taskBudget.durationSeconds()),
                        now));
        AgentRun coordinator = owned.coordinator();
        runs.insertAgentRun(
                new NewAgentRun(
                        agentRunId,
                        owned.run().orgId(),
                        owned.run().id(),
                        taskId,
                        coordinator != null ? coordinator.id() : null,
                        specification.agentType(),
                        "READY",
                        coordinator != null ? coordinator.depth() + 1 : 1,
                        "FRESH",
                        coordinator != null ? coordinator.permissionSnapshotJson() : "{}",
                        coordinator != null ? coordinator.permissionSnapshotHash() : null,
                        now));
        runs.assignTaskOwner(taskId, owned.run().orgId(), agentRunId, now);
        return taskId;
    }

    private void activate(AssistantRun run, UUID planId, OffsetDateTime now) {
        requireUpdated(
                plans.updateRunPlanningState(run.id(), run.orgId(), "PLANNED", "RUNNING", now),
                "activate planned Run");
        int released = plans.releaseRootPlanTasks(planId, run.id(), run.orgId(), now);
        appendEvent(
                run,
                null,
                "RUN_STARTED",
                json(Map.of("planId", planId.toString(), "readyTasks", released)));
    }

    private PlanView view(StoredPlan plan, UUID orgId, boolean reused) {
        List<PlanTask> planTasks = plans.findPlanTasks(plan.id(), plan.runId(), orgId);
        List<TaskEdge> edges = plans.findPlanEdges(plan.id(), plan.runId(), orgId);
        return new PlanView(
                plan.id(),
                plan.runId(),
                plan.version(),
                plan.status(),
                plan.goal(),
                plan.planHash(),
                plan.supersedesPlanId(),
                plan.approvalRequired(),
                plan.createdAt(),
                plan.decidedAt(),
                planTasks,
                edges,
                reused);
    }

    private Owned lockOwned(TenantContext tenant, UUID agentId, UUID runId) {
        UUID orgId = uuid(tenant.orgId(), "orgId");
        UUID userId = uuid(tenant.userId(), "userId");
        AssistantRun run =
                runs.lockOwnedRun(runId, orgId, userId, agentId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        if (SetStatus.TERMINAL.contains(run.status())) {
            throw new IllegalStateException("Cannot change a terminal Run " + runId);
        }
        return owned(run);
    }

    private Optional<Owned> findOwned(TenantContext tenant, UUID agentId, UUID runId) {
        UUID orgId = uuid(tenant.orgId(), "orgId");
        UUID userId = uuid(tenant.userId(), "userId");
        return runs.findOwnedRun(runId, orgId, userId, agentId).map(this::owned);
    }

    private Owned owned(AssistantRun run) {
        List<TaskNode> tasks = runs.findTasks(run.id(), run.orgId());
        TaskNode root =
                tasks.stream()
                        .filter(task -> task.parentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        AgentRun coordinator =
                runs.findAgentRuns(run.id(), run.orgId()).stream()
                        .filter(agent -> root.id().equals(agent.taskId()))
                        .findFirst()
                        .orElse(null);
        return new Owned(run, root, coordinator);
    }

    private void appendEvent(AssistantRun run, UUID taskId, String type, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        long sequence = runs.nextEventSequence(run.id(), run.orgId(), now);
        UUID eventId = UUID.randomUUID();
        runs.insertEvent(
                new NewEvent(
                        eventId,
                        run.orgId(),
                        run.userId(),
                        run.id(),
                        taskId,
                        sequence,
                        type,
                        payloadJson));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", run.id().toString());
        envelope.put("seq", sequence);
        envelope.put("taskId", taskId != null ? taskId.toString() : null);
        envelope.put("payload", JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class));
        runs.insertOutbox(
                new NewOutboxMessage(
                        UUID.randomUUID(),
                        run.orgId(),
                        run.id(),
                        "assistant_run",
                        type,
                        json(envelope)));
    }

    private static Map<String, PlanTask> reusableTasks(List<PlanTask> tasks) {
        Map<String, PlanTask> reusable = new HashMap<>();
        for (PlanTask task : tasks) {
            if ("SUCCEEDED".equals(task.status())) {
                reusable.put(task.clientTaskId(), task);
            }
        }
        return reusable;
    }

    private static OffsetDateTime deadline(OffsetDateTime now, Long durationSeconds) {
        return durationSeconds != null && durationSeconds > 0
                ? now.plusSeconds(durationSeconds)
                : null;
    }

    private static String json(Object value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " must be a UUID", e);
        }
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maxLength + " characters");
        }
        return value.trim();
    }

    private static void requireUpdated(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(operation + " affected " + count + " rows");
        }
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private record Owned(AssistantRun run, TaskNode rootTask, AgentRun coordinator) {}

    private static final class SetStatus {
        private static final java.util.Set<String> TERMINAL =
                java.util.Set.of("SUCCEEDED", "CANCELLED");
    }

    public record PlanView(
            UUID planId,
            UUID runId,
            int version,
            String status,
            String goal,
            String planHash,
            UUID supersedesPlanId,
            boolean approvalRequired,
            OffsetDateTime createdAt,
            OffsetDateTime decidedAt,
            List<PlanTask> tasks,
            List<TaskEdge> edges,
            boolean reused) {}

    public record ApprovalResult(
            UUID planId,
            String status,
            OffsetDateTime decidedAt,
            boolean reused,
            boolean activated) {}

    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(UUID runId) {
            super("Run not found: " + runId);
        }
    }

    public static class PlanNotFoundException extends RuntimeException {
        public PlanNotFoundException(UUID planId) {
            super("Plan not found: " + planId);
        }
    }
}
