/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.core.persistence.entity.AgentRunEntity;
import io.agentscope.saas.core.persistence.entity.AssistantRunEntity;
import io.agentscope.saas.core.persistence.entity.OrchestrationOutboxEntity;
import io.agentscope.saas.core.persistence.entity.RunAttemptEntity;
import io.agentscope.saas.core.persistence.entity.RunEventEntity;
import io.agentscope.saas.core.persistence.entity.TaskNodeEntity;
import io.agentscope.saas.core.persistence.repo.AgentRunRepository;
import io.agentscope.saas.core.persistence.repo.AssistantRunRepository;
import io.agentscope.saas.core.persistence.repo.OrchestrationOutboxRepository;
import io.agentscope.saas.core.persistence.repo.RunAttemptRepository;
import io.agentscope.saas.core.persistence.repo.RunEventRepository;
import io.agentscope.saas.core.persistence.repo.TaskNodeRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent control plane for assistant execution. The first rollout maps every chat request to a
 * single root task; later planners and workers add DAG nodes and attempts without changing the Run
 * identity or event protocol.
 */
@Service
public class RunOrchestrationService {

    /** RuntimeContext extra propagated to tools and future task workers. */
    public static final String ATTR_RUN_ID = "assistantRunId";

    /** RuntimeContext extra identifying the coordinator or subagent that owns the current call. */
    public static final String ATTR_AGENT_RUN_ID = "agentRunId";

    public static final String MODE_DIRECT = "DIRECT";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_SUCCEEDED = "SUCCEEDED";
    public static final String RUN_FAILED = "FAILED";
    public static final String RUN_CANCELLED = "CANCELLED";
    public static final String TASK_RUNNING = "RUNNING";
    public static final String TASK_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_FAILED = "FAILED";
    public static final String TASK_CANCELLED = "CANCELLED";
    public static final String ATTEMPT_RUNNING = "RUNNING";
    public static final String ATTEMPT_SUCCEEDED = "SUCCEEDED";
    public static final String ATTEMPT_FAILED = "FAILED";
    public static final String ATTEMPT_CANCELLED = "CANCELLED";
    private static final int MAX_COORDINATOR_ATTEMPTS = 33;
    private static final String COORDINATOR_CONTINUATION_INPUT =
            "{\"continuation\":true,\"prompt\":\"Review the completed subagent results and "
                    + "continue the original task. Produce the final answer, or delegate only "
                    + "when additional work is required.\"}";

    private final AssistantRunRepository runRepository;
    private final TaskNodeRepository taskRepository;
    private final AgentRunRepository agentRunRepository;
    private final RunAttemptRepository attemptRepository;
    private final RunEventRepository eventRepository;
    private final OrchestrationOutboxRepository outboxRepository;

    public RunOrchestrationService(
            AssistantRunRepository runRepository,
            TaskNodeRepository taskRepository,
            AgentRunRepository agentRunRepository,
            RunAttemptRepository attemptRepository,
            RunEventRepository eventRepository,
            OrchestrationOutboxRepository outboxRepository) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.agentRunRepository = agentRunRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
    }

    /** Creates the durable Run and its root task before the agent begins streaming. */
    @Transactional
    public RunHandle createDirectRun(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            String userMessage) {
        return createDirectRun(tenant, agentId, sessionId, triggerMessageId, userMessage, null);
    }

    /** Creates a direct Run, optionally protected by a caller-provided idempotency key. */
    @Transactional
    public RunHandle createDirectRun(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            String userMessage,
            String idempotencyKey) {
        UUID orgId = uuid(tenant.orgId(), "orgId");
        UUID userId = uuid(tenant.userId(), "userId");
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey != null) {
            Optional<AssistantRunEntity> existing =
                    runRepository.findByOrgIdAndUserIdAndAgentIdAndIdempotencyKey(
                            orgId, userId, agentId, normalizedKey);
            if (existing.isPresent()) {
                AssistantRunEntity run = existing.get();
                return new RunHandle(
                        run.getId(), null, null, null, run.getAgentId(), run.getSessionId(), true);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();

        AssistantRunEntity run = new AssistantRunEntity();
        run.setId(UUID.randomUUID());
        run.setOrgId(orgId);
        run.setUserId(userId);
        run.setAgentId(agentId);
        run.setSessionId(sessionId);
        run.setTriggerMessageId(triggerMessageId);
        run.setIdempotencyKey(normalizedKey);
        run.setMode(MODE_DIRECT);
        run.setStatus(RUN_RUNNING);
        run.setCancelRequested(false);
        run.setNextEventSeq(0);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run = runRepository.save(run);

        TaskNodeEntity root = new TaskNodeEntity();
        root.setId(UUID.randomUUID());
        root.setOrgId(orgId);
        root.setRunId(run.getId());
        root.setTitle(titleFor(userMessage));
        root.setTaskType("agent");
        root.setStatus(TASK_RUNNING);
        root.setPriority(0);
        root.setInputJson("{}");
        root.setExpectedOutputJson("{}");
        root.setOutputJson("{}");
        root.setAcceptanceJson("[]");
        root.setWorkspaceMode("NONE");
        root.setMaxAttempts(MAX_COORDINATOR_ATTEMPTS);
        root.setRetryMode("IDEMPOTENT");
        root.setRetryBaseSeconds(2);
        root.setUpdatedAt(now);
        taskRepository.save(root);

        AgentRunEntity agentRun = new AgentRunEntity();
        agentRun.setId(UUID.randomUUID());
        agentRun.setOrgId(orgId);
        agentRun.setRunId(run.getId());
        agentRun.setTaskId(root.getId());
        agentRun.setAgentType("assistant");
        agentRun.setStatus(RUN_RUNNING);
        agentRun.setDepth(0);
        agentRun.setContextPolicy("FRESH");
        agentRun.setUpdatedAt(now);
        agentRunRepository.save(agentRun);

        root.setOwnerAgentRunId(agentRun.getId());
        taskRepository.save(root);

        RunAttemptEntity attempt = new RunAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setOrgId(orgId);
        attempt.setRunId(run.getId());
        attempt.setTaskId(root.getId());
        attempt.setAgentRunId(agentRun.getId());
        attempt.setAttemptNo(1);
        attempt.setStatus(ATTEMPT_RUNNING);
        attempt.setIdempotencyKey("direct:" + run.getId());
        attempt.setStartedAt(now);
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);

        appendEvent(run, root.getId(), "RUN_CREATED", "{\"mode\":\"DIRECT\"}");
        appendEvent(run, root.getId(), "RUN_STARTED", "{}");
        appendEvent(run, root.getId(), "TASK_STARTED", "{}");
        return new RunHandle(
                run.getId(),
                root.getId(),
                agentRun.getId(),
                attempt.getId(),
                agentId,
                sessionId,
                false);
    }

    @Transactional(readOnly = true)
    public Optional<RunView> findByIdempotencyKey(
            TenantContext tenant, UUID agentId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }
        return runRepository
                .findByOrgIdAndUserIdAndAgentIdAndIdempotencyKey(
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId,
                        normalizedKey)
                .map(this::toView);
    }

    /**
     * Marks the coordinator task successful. A Run with durable children remains RUNNING until the
     * scheduler reaches a terminal state for every child.
     */
    @Transactional
    public void markSucceeded(TenantContext tenant, UUID agentId, UUID runId) {
        AssistantRunEntity run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.getStatus())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        completeCoordinator(run, now);
        List<TaskNodeEntity> children =
                taskRepository
                        .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                        .stream()
                        .filter(task -> task.getParentId() != null)
                        .toList();
        boolean childrenPending =
                children.stream()
                        .anyMatch(
                                task ->
                                        !isTerminalTaskStatus(task.getStatus())
                                                || "MANUAL_ACTION".equals(task.getStatus()));
        if (childrenPending) {
            run.setUpdatedAt(now);
            appendEvent(run, null, "COORDINATOR_SUCCEEDED", "{}");
        } else if (!children.isEmpty()) {
            scheduleCoordinatorContinuation(run, now);
        } else {
            run.setStatus(RUN_SUCCEEDED);
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            appendEvent(run, null, "RUN_SUCCEEDED", "{}");
        }
    }

    /** Adds an idempotent durable background subagent task to an active Run. */
    @Transactional
    public SubagentTaskHandle createSubagentTask(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            UUID parentAgentRunId,
            String externalTaskId,
            String subagentType,
            String subSessionId,
            String inputJson,
            SubagentPolicy policy) {
        AssistantRunEntity run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.getStatus())) {
            throw new IllegalStateException("Cannot add a task to terminal Run " + runId);
        }
        String taskKey = required(externalTaskId, "taskId", 255);
        Optional<TaskNodeEntity> existing =
                taskRepository.findByRunIdAndOrgIdAndExternalTaskId(
                        run.getId(), run.getOrgId(), taskKey);
        if (existing.isPresent()) {
            TaskNodeEntity task = existing.get();
            return new SubagentTaskHandle(
                    task.getId(), task.getOwnerAgentRunId(), task.getStatus(), true);
        }

        List<TaskNodeEntity> tasks =
                taskRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId());
        TaskNodeEntity root =
                tasks.stream()
                        .filter(task -> task.getParentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        List<AgentRunEntity> agentRuns =
                agentRunRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(
                        run.getId(), run.getOrgId());
        AgentRunEntity parentAgentRun = resolveParentAgentRun(agentRuns, parentAgentRunId);
        validateSubagentPolicy(tasks, agentRuns, parentAgentRun, policy);

        OffsetDateTime now = OffsetDateTime.now();
        TaskNodeEntity task = new TaskNodeEntity();
        task.setId(UUID.randomUUID());
        task.setOrgId(run.getOrgId());
        task.setRunId(run.getId());
        task.setParentId(root.getId());
        task.setExternalTaskId(taskKey);
        task.setSubSessionId(required(subSessionId, "subSessionId", 255));
        task.setTitle(titleFor(subagentType + ": " + taskKey));
        task.setTaskType("subagent");
        task.setStatus("READY");
        task.setPriority(0);
        task.setInputJson(validJsonObject(inputJson));
        task.setExpectedOutputJson("{}");
        task.setOutputJson("{}");
        task.setAcceptanceJson("[]");
        task.setWorkspaceMode("ISOLATED_ATTEMPT");
        task.setMaxAttempts(3);
        task.setRetryMode("IDEMPOTENT");
        task.setRetryBaseSeconds(2);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        AgentRunEntity child = new AgentRunEntity();
        child.setId(UUID.randomUUID());
        child.setOrgId(run.getOrgId());
        child.setRunId(run.getId());
        child.setTaskId(task.getId());
        child.setParentAgentRunId(parentAgentRun.getId());
        child.setAgentType(required(subagentType, "subagentType", 128));
        child.setStatus("READY");
        child.setDepth(parentAgentRun.getDepth() + 1);
        child.setContextPolicy("FRESH_RELEVANT");
        child.setUpdatedAt(now);
        agentRunRepository.save(child);

        task.setOwnerAgentRunId(child.getId());
        taskRepository.save(task);
        appendEvent(
                run,
                task.getId(),
                "TASK_READY",
                JsonUtils.getJsonCodec()
                        .toJson(Map.of("taskId", taskKey, "agentType", subagentType)));
        return new SubagentTaskHandle(task.getId(), child.getId(), task.getStatus(), false);
    }

    private static AgentRunEntity resolveParentAgentRun(
            List<AgentRunEntity> agentRuns, UUID requestedParentId) {
        return agentRuns.stream()
                .filter(
                        candidate ->
                                requestedParentId != null
                                        ? requestedParentId.equals(candidate.getId())
                                        : candidate.getParentAgentRunId() == null)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        requestedParentId != null
                                                ? "Parent AgentRun does not belong to Run"
                                                : "Run has no coordinator agent"));
    }

    private static void validateSubagentPolicy(
            List<TaskNodeEntity> tasks,
            List<AgentRunEntity> agentRuns,
            AgentRunEntity parent,
            SubagentPolicy policy) {
        SubagentPolicy requiredPolicy = policy != null ? policy : new SubagentPolicy(3, 8, 32);
        if (parent.getDepth() + 1 > requiredPolicy.maxDepth()) {
            throw new IllegalStateException(
                    "Durable subagent maximum depth exceeded: " + requiredPolicy.maxDepth());
        }
        long childCount =
                agentRuns.stream()
                        .filter(candidate -> parent.getId().equals(candidate.getParentAgentRunId()))
                        .count();
        if (childCount >= requiredPolicy.maxChildrenPerAgent()) {
            throw new IllegalStateException(
                    "Durable subagent fan-out limit exceeded: "
                            + requiredPolicy.maxChildrenPerAgent());
        }
        long durableTaskCount =
                tasks.stream().filter(candidate -> candidate.getExternalTaskId() != null).count();
        if (durableTaskCount >= requiredPolicy.maxTasksPerRun()) {
            throw new IllegalStateException(
                    "Durable subagent task limit exceeded: " + requiredPolicy.maxTasksPerRun());
        }
    }

    /** Cancels one durable child and invalidates any live Attempt lease. */
    @Transactional
    public boolean cancelSubagentTask(TenantContext tenant, UUID agentId, UUID runId, UUID taskId) {
        AssistantRunEntity run = lockOwnedRun(tenant, agentId, runId);
        TaskNodeEntity task =
                taskRepository
                        .findById(taskId)
                        .filter(candidate -> run.getId().equals(candidate.getRunId()))
                        .filter(candidate -> run.getOrgId().equals(candidate.getOrgId()))
                        .orElseThrow(
                                () -> new IllegalArgumentException("Task does not belong to Run"));
        if (isTerminalTaskStatus(task.getStatus())) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        task.setStatus(TASK_CANCELLED);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        agentRunRepository
                .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                .stream()
                .filter(agentRun -> task.getId().equals(agentRun.getTaskId()))
                .filter(agentRun -> !isTerminalTaskStatus(agentRun.getStatus()))
                .forEach(
                        agentRun -> {
                            agentRun.setStatus(TASK_CANCELLED);
                            agentRun.setCompletedAt(now);
                            agentRun.setUpdatedAt(now);
                            agentRunRepository.save(agentRun);
                        });
        attemptRepository
                .findByRunIdAndOrgIdOrderByAttemptNoAsc(run.getId(), run.getOrgId())
                .stream()
                .filter(attempt -> task.getId().equals(attempt.getTaskId()))
                .filter(attempt -> !isTerminalAttemptStatus(attempt.getStatus()))
                .forEach(
                        attempt -> {
                            attempt.setStatus(ATTEMPT_CANCELLED);
                            attempt.setLeaseExpiresAt(null);
                            attempt.setCompletedAt(now);
                            attempt.setUpdatedAt(now);
                            attemptRepository.save(attempt);
                        });
        appendEvent(run, task.getId(), "TASK_CANCELLED", "{}");

        List<TaskNodeEntity> allTasks =
                taskRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId());
        boolean allChildrenSettled =
                allTasks.stream()
                        .filter(candidate -> candidate.getParentId() != null)
                        .allMatch(
                                candidate ->
                                        TASK_SUCCEEDED.equals(candidate.getStatus())
                                                || TASK_CANCELLED.equals(candidate.getStatus()));
        boolean coordinatorCompleted =
                allTasks.stream()
                        .filter(candidate -> candidate.getParentId() == null)
                        .anyMatch(candidate -> TASK_SUCCEEDED.equals(candidate.getStatus()));
        if (allChildrenSettled && coordinatorCompleted && RUN_RUNNING.equals(run.getStatus())) {
            scheduleCoordinatorContinuation(run, now);
        }
        return true;
    }

    /** True while a continuation has delegated more work that must settle before its final reply. */
    @Transactional(readOnly = true)
    public boolean hasUnsettledChildren(UUID runId) {
        return runRepository
                .findById(runId)
                .map(
                        run ->
                                taskRepository
                                        .findByRunIdAndOrgIdOrderByCreatedAtAsc(
                                                run.getId(), run.getOrgId())
                                        .stream()
                                        .filter(task -> task.getParentId() != null)
                                        .anyMatch(
                                                task ->
                                                        !TASK_SUCCEEDED.equals(task.getStatus())
                                                                && !TASK_CANCELLED.equals(
                                                                        task.getStatus())))
                .orElse(true);
    }

    /** Persists an execution failure. Terminal states are immutable. */
    @Transactional
    public void markFailed(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            String failureCode,
            String failureMessage) {
        AssistantRunEntity run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.getStatus())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        run.setStatus(RUN_FAILED);
        run.setFailureCode(truncate(failureCode, 128));
        run.setFailureMessage(truncate(failureMessage, 2000));
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        completeExecution(run, TASK_FAILED, ATTEMPT_FAILED, now, failureCode, failureMessage);
        appendEvent(run, null, "RUN_FAILED", "{}");
    }

    /**
     * Records an explicit user cancellation. The caller performs the in-memory agent interrupt after
     * this transaction commits; a disconnect from SSE never calls this method.
     */
    @Transactional
    public Optional<CancelledRun> cancel(TenantContext tenant, UUID agentId, UUID runId) {
        Optional<AssistantRunEntity> maybeRun =
                runRepository.lockOwnedRun(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId);
        if (maybeRun.isEmpty()) {
            return Optional.empty();
        }
        AssistantRunEntity run = maybeRun.get();
        if (!RUN_RUNNING.equals(run.getStatus())) {
            return Optional.of(
                    new CancelledRun(run.getId(), run.getAgentId(), run.getSessionId(), false));
        }
        OffsetDateTime now = OffsetDateTime.now();
        run.setCancelRequested(true);
        run.setStatus(RUN_CANCELLED);
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        completeExecution(run, TASK_CANCELLED, ATTEMPT_CANCELLED, now, null, null);
        appendEvent(run, null, "RUN_CANCELLED", "{}");
        return Optional.of(
                new CancelledRun(run.getId(), run.getAgentId(), run.getSessionId(), true));
    }

    @Transactional(readOnly = true)
    public Optional<RunView> getRun(TenantContext tenant, UUID agentId, UUID runId) {
        return runRepository
                .findByIdAndOrgIdAndUserIdAndAgentId(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId)
                .map(this::toView);
    }

    @Transactional(readOnly = true)
    public List<TaskView> getTasks(TenantContext tenant, UUID agentId, UUID runId) {
        AssistantRunEntity run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        return taskRepository
                .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                .stream()
                .map(this::toTaskView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AttemptView> getAttempts(TenantContext tenant, UUID agentId, UUID runId) {
        AssistantRunEntity run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        return attemptRepository
                .findByRunIdAndOrgIdOrderByAttemptNoAsc(run.getId(), run.getOrgId())
                .stream()
                .map(this::toAttemptView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RunEventView> getEvents(
            TenantContext tenant, UUID agentId, UUID runId, long afterSeq, int limit) {
        AssistantRunEntity run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return eventRepository
                .findByRunIdAndOrgIdAndUserIdAndSeqGreaterThanOrderBySeqAsc(
                        run.getId(),
                        run.getOrgId(),
                        run.getUserId(),
                        Math.max(0, afterSeq),
                        PageRequest.of(0, boundedLimit))
                .stream()
                .map(this::toEventView)
                .toList();
    }

    private AssistantRunEntity lockOwnedRun(TenantContext tenant, UUID agentId, UUID runId) {
        return runRepository
                .lockOwnedRun(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    private Optional<AssistantRunEntity> getRunEntity(
            TenantContext tenant, UUID agentId, UUID runId) {
        return runRepository.findByIdAndOrgIdAndUserIdAndAgentId(
                runId, uuid(tenant.orgId(), "orgId"), uuid(tenant.userId(), "userId"), agentId);
    }

    private void completeExecution(
            AssistantRunEntity run,
            String taskStatus,
            String attemptStatus,
            OffsetDateTime completedAt,
            String errorCode,
            String errorMessage) {
        for (TaskNodeEntity task :
                taskRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(
                        run.getId(), run.getOrgId())) {
            if (!isTerminalTaskStatus(task.getStatus())) {
                task.setStatus(taskStatus);
                task.setCompletedAt(completedAt);
                task.setUpdatedAt(completedAt);
                taskRepository.save(task);
                appendEvent(run, task.getId(), "TASK_" + taskStatus, "{}");
            }
        }
        for (AgentRunEntity agentRun :
                agentRunRepository.findByRunIdAndOrgIdOrderByCreatedAtAsc(
                        run.getId(), run.getOrgId())) {
            if (RUN_RUNNING.equals(agentRun.getStatus())) {
                agentRun.setStatus(taskStatus);
                agentRun.setCompletedAt(completedAt);
                agentRun.setUpdatedAt(completedAt);
                agentRunRepository.save(agentRun);
            }
        }
        for (RunAttemptEntity attempt :
                attemptRepository.findByRunIdAndOrgIdOrderByAttemptNoAsc(
                        run.getId(), run.getOrgId())) {
            if (!isTerminalAttemptStatus(attempt.getStatus())) {
                attempt.setStatus(attemptStatus);
                attempt.setErrorCode(truncate(errorCode, 128));
                attempt.setErrorMessage(truncate(errorMessage, 2000));
                attempt.setCompletedAt(completedAt);
                attempt.setUpdatedAt(completedAt);
                attemptRepository.save(attempt);
            }
        }
    }

    private void completeCoordinator(AssistantRunEntity run, OffsetDateTime completedAt) {
        TaskNodeEntity root =
                taskRepository
                        .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                        .stream()
                        .filter(task -> task.getParentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        if (!isTerminalTaskStatus(root.getStatus())) {
            root.setStatus(TASK_SUCCEEDED);
            root.setCompletedAt(completedAt);
            root.setUpdatedAt(completedAt);
            taskRepository.save(root);
            appendEvent(run, root.getId(), "TASK_SUCCEEDED", "{}");
        }
        agentRunRepository
                .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                .stream()
                .filter(agentRun -> agentRun.getParentAgentRunId() == null)
                .filter(agentRun -> RUN_RUNNING.equals(agentRun.getStatus()))
                .forEach(
                        agentRun -> {
                            agentRun.setStatus(TASK_SUCCEEDED);
                            agentRun.setCompletedAt(completedAt);
                            agentRun.setUpdatedAt(completedAt);
                            agentRunRepository.save(agentRun);
                        });
        attemptRepository
                .findByRunIdAndOrgIdOrderByAttemptNoAsc(run.getId(), run.getOrgId())
                .stream()
                .filter(attempt -> root.getId().equals(attempt.getTaskId()))
                .filter(attempt -> !isTerminalAttemptStatus(attempt.getStatus()))
                .forEach(
                        attempt -> {
                            attempt.setStatus(ATTEMPT_SUCCEEDED);
                            attempt.setCompletedAt(completedAt);
                            attempt.setUpdatedAt(completedAt);
                            attemptRepository.save(attempt);
                        });
    }

    private void scheduleCoordinatorContinuation(AssistantRunEntity run, OffsetDateTime now) {
        TaskNodeEntity root =
                taskRepository
                        .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                        .stream()
                        .filter(task -> task.getParentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        if (!TASK_SUCCEEDED.equals(root.getStatus())) {
            return;
        }
        root.setStatus("READY");
        root.setInputJson(COORDINATOR_CONTINUATION_INPUT);
        root.setMaxAttempts(Math.max(root.getMaxAttempts(), MAX_COORDINATOR_ATTEMPTS));
        root.setNextAttemptAt(null);
        root.setCompletedAt(null);
        root.setUpdatedAt(now);
        taskRepository.save(root);

        agentRunRepository
                .findByRunIdAndOrgIdOrderByCreatedAtAsc(run.getId(), run.getOrgId())
                .stream()
                .filter(agentRun -> agentRun.getParentAgentRunId() == null)
                .findFirst()
                .ifPresent(
                        agentRun -> {
                            agentRun.setStatus("READY");
                            agentRun.setCompletedAt(null);
                            agentRun.setUpdatedAt(now);
                            agentRunRepository.save(agentRun);
                        });
        run.setCompletedAt(null);
        run.setUpdatedAt(now);
        appendEvent(run, root.getId(), "COORDINATOR_CONTINUATION_READY", "{}");
    }

    private void appendEvent(
            AssistantRunEntity run, UUID taskId, String eventType, String payloadJson) {
        RunEventEntity event = new RunEventEntity();
        event.setId(UUID.randomUUID());
        event.setOrgId(run.getOrgId());
        event.setUserId(run.getUserId());
        event.setRunId(run.getId());
        event.setTaskId(taskId);
        long seq = run.getNextEventSeq() + 1;
        run.setNextEventSeq(seq);
        event.setSeq(seq);
        event.setEventType(eventType);
        event.setPayloadJson(payloadJson);
        eventRepository.save(event);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getId().toString());
        envelope.put("runId", run.getId().toString());
        envelope.put("seq", seq);
        envelope.put("taskId", taskId != null ? taskId.toString() : null);
        envelope.put("payload", JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class));

        OrchestrationOutboxEntity outbox = new OrchestrationOutboxEntity();
        outbox.setId(UUID.randomUUID());
        outbox.setOrgId(run.getOrgId());
        outbox.setAggregateId(run.getId());
        outbox.setAggregateType("assistant_run");
        outbox.setEventType(eventType);
        outbox.setPayloadJson(JsonUtils.getJsonCodec().toJson(envelope));
        outbox.setAttempts(0);
        outboxRepository.save(outbox);
    }

    private RunView toView(AssistantRunEntity run) {
        return new RunView(
                run.getId(),
                run.getSessionId(),
                run.getAgentId(),
                run.getMode(),
                run.getStatus(),
                run.isCancelRequested(),
                run.getFailureCode(),
                run.getFailureMessage(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    private TaskView toTaskView(TaskNodeEntity task) {
        return new TaskView(
                task.getId(),
                task.getParentId(),
                task.getTitle(),
                task.getTaskType(),
                task.getStatus(),
                task.getWorkspaceMode(),
                task.getCreatedAt(),
                task.getCompletedAt());
    }

    private RunEventView toEventView(RunEventEntity event) {
        return new RunEventView(
                event.getSeq(),
                event.getEventType(),
                event.getTaskId(),
                event.getPayloadJson(),
                event.getCreatedAt());
    }

    private AttemptView toAttemptView(RunAttemptEntity attempt) {
        return new AttemptView(
                attempt.getId(),
                attempt.getTaskId(),
                attempt.getAgentRunId(),
                attempt.getAttemptNo(),
                attempt.getStatus(),
                attempt.getErrorCode(),
                attempt.getErrorMessage(),
                attempt.getStartedAt(),
                attempt.getCompletedAt());
    }

    private static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for durable orchestration");
        }
        return UUID.fromString(value);
    }

    private static String titleFor(String message) {
        String trimmed = message == null ? "Assistant request" : message.trim();
        return truncate(trimmed.isEmpty() ? "Assistant request" : trimmed, 500);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("requestId must be at most 255 characters");
        }
        return normalized;
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return truncate(value.trim(), maxLength);
    }

    private static String validJsonObject(String value) {
        String candidate = value == null || value.isBlank() ? "{}" : value;
        Object decoded = JsonUtils.getJsonCodec().fromJson(candidate, Object.class);
        if (!(decoded instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("inputJson must be a JSON object");
        }
        return candidate;
    }

    private static boolean isTerminalTaskStatus(String status) {
        return TASK_SUCCEEDED.equals(status)
                || TASK_FAILED.equals(status)
                || TASK_CANCELLED.equals(status)
                || "MANUAL_ACTION".equals(status);
    }

    private static boolean isTerminalAttemptStatus(String status) {
        return ATTEMPT_SUCCEEDED.equals(status)
                || ATTEMPT_FAILED.equals(status)
                || ATTEMPT_CANCELLED.equals(status)
                || "TIMED_OUT".equals(status)
                || "ABANDONED".equals(status);
    }

    public record RunHandle(
            UUID runId,
            UUID rootTaskId,
            UUID rootAgentRunId,
            UUID rootAttemptId,
            UUID agentId,
            UUID sessionId,
            boolean reused) {}

    public record CancelledRun(UUID runId, UUID agentId, UUID sessionId, boolean interrupted) {}

    public record SubagentTaskHandle(UUID taskId, UUID agentRunId, String status, boolean reused) {}

    public record SubagentPolicy(int maxDepth, int maxChildrenPerAgent, int maxTasksPerRun) {
        public SubagentPolicy {
            if (maxDepth < 1 || maxChildrenPerAgent < 1 || maxTasksPerRun < 1) {
                throw new IllegalArgumentException("Subagent policy limits must be positive");
            }
        }
    }

    public record RunView(
            UUID id,
            UUID sessionId,
            UUID agentId,
            String mode,
            String status,
            boolean cancelRequested,
            String failureCode,
            String failureMessage,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {}

    public record TaskView(
            UUID id,
            UUID parentId,
            String title,
            String taskType,
            String status,
            String workspaceMode,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {}

    public record RunEventView(
            long seq,
            String eventType,
            UUID taskId,
            String payloadJson,
            OffsetDateTime createdAt) {}

    public record AttemptView(
            UUID id,
            UUID taskId,
            UUID agentRunId,
            int attemptNo,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {}

    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(UUID runId) {
            super("Run not found: " + runId);
        }
    }
}
