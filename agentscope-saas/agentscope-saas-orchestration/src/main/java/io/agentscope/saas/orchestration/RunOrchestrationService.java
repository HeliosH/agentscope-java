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
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.AssistantRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAgentRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewRun;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewTask;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.RunAttempt;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.RunEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.TaskNode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final RunOrchestrationRepository repository;

    public RunOrchestrationService(RunOrchestrationRepository repository) {
        this.repository = repository;
    }

    /** Creates the durable Run and its root task before the agent begins streaming. */
    @Transactional
    public RunHandle createDirectRun(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            String userMessage) {
        return createDirectRun(
                tenant,
                agentId,
                sessionId,
                triggerMessageId,
                userMessage,
                null,
                RunPolicy.unlimited());
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
        return createDirectRun(
                tenant,
                agentId,
                sessionId,
                triggerMessageId,
                userMessage,
                idempotencyKey,
                RunPolicy.unlimited());
    }

    /** Creates a direct Run with immutable resource and permission governance snapshots. */
    @Transactional
    public RunHandle createDirectRun(
            TenantContext tenant,
            UUID agentId,
            UUID sessionId,
            UUID triggerMessageId,
            String userMessage,
            String idempotencyKey,
            RunPolicy requestedPolicy) {
        UUID orgId = uuid(tenant.orgId(), "orgId");
        UUID userId = uuid(tenant.userId(), "userId");
        RunPolicy policy = requestedPolicy != null ? requestedPolicy : RunPolicy.unlimited();
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey != null) {
            Optional<AssistantRun> existing =
                    repository.findByIdempotencyKey(orgId, userId, agentId, normalizedKey);
            if (existing.isPresent()) {
                AssistantRun run = existing.get();
                return new RunHandle(
                        run.id(), null, null, null, run.agentId(), run.sessionId(), true);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        UUID runId = UUID.randomUUID();
        UUID rootTaskId = UUID.randomUUID();
        UUID rootAgentRunId = UUID.randomUUID();
        UUID rootAttemptId = UUID.randomUUID();
        repository.insertRun(
                new NewRun(
                        runId,
                        orgId,
                        userId,
                        agentId,
                        sessionId,
                        triggerMessageId,
                        normalizedKey,
                        MODE_DIRECT,
                        RUN_RUNNING,
                        positiveLimit(policy.runTokenBudget()),
                        positiveLimit(policy.runCostBudgetMicros()),
                        positiveLimit(policy.runModelCallBudget()),
                        deadline(now, policy.runTimeoutSeconds()),
                        now,
                        now));
        repository.insertTask(
                new NewTask(
                        rootTaskId,
                        orgId,
                        runId,
                        null,
                        null,
                        null,
                        titleFor(userMessage),
                        "agent",
                        TASK_RUNNING,
                        0,
                        "{}",
                        "{}",
                        "{}",
                        "[]",
                        "NONE",
                        MAX_COORDINATOR_ATTEMPTS,
                        "IDEMPOTENT",
                        2,
                        positiveLimit(policy.taskTokenBudget()),
                        positiveLimit(policy.taskCostBudgetMicros()),
                        positiveLimit(policy.taskModelCallBudget()),
                        deadline(now, policy.taskTimeoutSeconds()),
                        now));

        PermissionSnapshotIntegrity.Snapshot permissionSnapshot =
                PermissionSnapshotIntegrity.canonicalize(
                        validJsonObject(policy.permissionSnapshotJson()));
        repository.insertAgentRun(
                new NewAgentRun(
                        rootAgentRunId,
                        orgId,
                        runId,
                        rootTaskId,
                        null,
                        "assistant",
                        RUN_RUNNING,
                        0,
                        "FRESH",
                        permissionSnapshot.json(),
                        permissionSnapshot.hash(),
                        now));
        repository.assignTaskOwner(rootTaskId, orgId, rootAgentRunId, now);
        repository.insertAttempt(
                new NewAttempt(
                        rootAttemptId,
                        orgId,
                        runId,
                        rootTaskId,
                        rootAgentRunId,
                        1,
                        ATTEMPT_RUNNING,
                        "direct:" + runId,
                        now,
                        now));

        AssistantRun run =
                repository
                        .findOwnedRun(runId, orgId, userId, agentId)
                        .orElseThrow(() -> new IllegalStateException("Created Run was not found"));
        appendEvent(run, rootTaskId, "RUN_CREATED", "{\"mode\":\"DIRECT\"}");
        appendEvent(run, rootTaskId, "RUN_STARTED", "{}");
        appendEvent(run, rootTaskId, "TASK_STARTED", "{}");
        appendEvent(
                run,
                rootTaskId,
                "AGENT_PERMISSION_SNAPSHOT",
                JsonUtils.getJsonCodec()
                        .toJson(
                                Map.of(
                                        "agentRunId",
                                        rootAgentRunId.toString(),
                                        "snapshotHash",
                                        valueOrEmpty(permissionSnapshot.hash()))));
        return new RunHandle(
                runId, rootTaskId, rootAgentRunId, rootAttemptId, agentId, sessionId, false);
    }

    @Transactional(readOnly = true)
    public Optional<RunView> findByIdempotencyKey(
            TenantContext tenant, UUID agentId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }
        return repository
                .findByIdempotencyKey(
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
        AssistantRun run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.status())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        completeCoordinator(run, now);
        List<TaskNode> children =
                repository.findTasks(run.id(), run.orgId()).stream()
                        .filter(task -> task.parentId() != null)
                        .toList();
        boolean childrenPending =
                children.stream()
                        .anyMatch(
                                task ->
                                        !isTerminalTaskStatus(task.status())
                                                || "MANUAL_ACTION".equals(task.status()));
        if (childrenPending) {
            repository.touchRun(run.id(), run.orgId(), now);
            appendEvent(run, null, "COORDINATOR_SUCCEEDED", "{}");
        } else if (!children.isEmpty()) {
            scheduleCoordinatorContinuation(run, now);
        } else {
            repository.completeRun(
                    run.id(), run.orgId(), RUN_SUCCEEDED, false, null, null, now, now);
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
        AssistantRun run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.status())) {
            throw new IllegalStateException("Cannot add a task to terminal Run " + runId);
        }
        String taskKey = required(externalTaskId, "taskId", 255);
        Optional<TaskNode> existing =
                repository.findTaskByExternalId(run.id(), run.orgId(), taskKey);
        if (existing.isPresent()) {
            TaskNode task = existing.get();
            return new SubagentTaskHandle(task.id(), task.ownerAgentRunId(), task.status(), true);
        }

        List<TaskNode> tasks = repository.findTasks(run.id(), run.orgId());
        TaskNode root =
                tasks.stream()
                        .filter(task -> task.parentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        List<AgentRun> agentRuns = repository.findAgentRuns(run.id(), run.orgId());
        AgentRun parentAgentRun = resolveParentAgentRun(agentRuns, parentAgentRunId);
        validateSubagentPolicy(tasks, agentRuns, parentAgentRun, policy);

        OffsetDateTime now = OffsetDateTime.now();
        UUID taskId = UUID.randomUUID();
        UUID childAgentRunId = UUID.randomUUID();
        repository.insertTask(
                new NewTask(
                        taskId,
                        run.orgId(),
                        run.id(),
                        root.id(),
                        taskKey,
                        required(subSessionId, "subSessionId", 255),
                        titleFor(subagentType + ": " + taskKey),
                        "subagent",
                        "READY",
                        0,
                        validJsonObject(inputJson),
                        "{}",
                        "{}",
                        "[]",
                        "ISOLATED_ATTEMPT",
                        3,
                        "IDEMPOTENT",
                        2,
                        positiveLimit(policy.taskTokenBudget()),
                        positiveLimit(policy.taskCostBudgetMicros()),
                        positiveLimit(policy.taskModelCallBudget()),
                        deadline(now, policy.taskTimeoutSeconds()),
                        now));
        repository.insertAgentRun(
                new NewAgentRun(
                        childAgentRunId,
                        run.orgId(),
                        run.id(),
                        taskId,
                        parentAgentRun.id(),
                        required(subagentType, "subagentType", 128),
                        "READY",
                        parentAgentRun.depth() + 1,
                        "FRESH_RELEVANT",
                        parentAgentRun.permissionSnapshotJson(),
                        parentAgentRun.permissionSnapshotHash(),
                        now));
        repository.assignTaskOwner(taskId, run.orgId(), childAgentRunId, now);
        appendEvent(
                run,
                taskId,
                "TASK_READY",
                JsonUtils.getJsonCodec()
                        .toJson(Map.of("taskId", taskKey, "agentType", subagentType)));
        appendEvent(
                run,
                taskId,
                "SUBAGENT_PERMISSION_INHERITED",
                JsonUtils.getJsonCodec()
                        .toJson(
                                Map.of(
                                        "parentAgentRunId",
                                        parentAgentRun.id().toString(),
                                        "childAgentRunId",
                                        childAgentRunId.toString(),
                                        "snapshotHash",
                                        valueOrEmpty(parentAgentRun.permissionSnapshotHash()))));
        return new SubagentTaskHandle(taskId, childAgentRunId, "READY", false);
    }

    private static AgentRun resolveParentAgentRun(
            List<AgentRun> agentRuns, UUID requestedParentId) {
        return agentRuns.stream()
                .filter(
                        candidate ->
                                requestedParentId != null
                                        ? requestedParentId.equals(candidate.id())
                                        : candidate.parentAgentRunId() == null)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        requestedParentId != null
                                                ? "Parent AgentRun does not belong to Run"
                                                : "Run has no coordinator agent"));
    }

    private static void validateSubagentPolicy(
            List<TaskNode> tasks,
            List<AgentRun> agentRuns,
            AgentRun parent,
            SubagentPolicy policy) {
        SubagentPolicy requiredPolicy = policy != null ? policy : new SubagentPolicy(3, 8, 32);
        if (parent.depth() + 1 > requiredPolicy.maxDepth()) {
            throw new IllegalStateException(
                    "Durable subagent maximum depth exceeded: " + requiredPolicy.maxDepth());
        }
        long childCount =
                agentRuns.stream()
                        .filter(candidate -> parent.id().equals(candidate.parentAgentRunId()))
                        .count();
        if (childCount >= requiredPolicy.maxChildrenPerAgent()) {
            throw new IllegalStateException(
                    "Durable subagent fan-out limit exceeded: "
                            + requiredPolicy.maxChildrenPerAgent());
        }
        long durableTaskCount =
                tasks.stream().filter(candidate -> candidate.externalTaskId() != null).count();
        if (durableTaskCount >= requiredPolicy.maxTasksPerRun()) {
            throw new IllegalStateException(
                    "Durable subagent task limit exceeded: " + requiredPolicy.maxTasksPerRun());
        }
    }

    /** Cancels one durable child and invalidates any live Attempt lease. */
    @Transactional
    public boolean cancelSubagentTask(TenantContext tenant, UUID agentId, UUID runId, UUID taskId) {
        AssistantRun run = lockOwnedRun(tenant, agentId, runId);
        TaskNode task =
                repository
                        .findTask(taskId, run.id(), run.orgId())
                        .orElseThrow(
                                () -> new IllegalArgumentException("Task does not belong to Run"));
        if (isTerminalTaskStatus(task.status())) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        repository.completeTask(task.id(), run.orgId(), TASK_CANCELLED, null, null, now, now);
        repository.findAgentRuns(run.id(), run.orgId()).stream()
                .filter(agentRun -> task.id().equals(agentRun.taskId()))
                .filter(agentRun -> !isTerminalTaskStatus(agentRun.status()))
                .forEach(
                        agentRun ->
                                repository.updateAgentRunStatus(
                                        agentRun.id(), run.orgId(), TASK_CANCELLED, now, now));
        repository.findAttempts(run.id(), run.orgId()).stream()
                .filter(attempt -> task.id().equals(attempt.taskId()))
                .filter(attempt -> !isTerminalAttemptStatus(attempt.status()))
                .forEach(
                        attempt ->
                                repository.updateAttemptStatus(
                                        attempt.id(),
                                        run.orgId(),
                                        ATTEMPT_CANCELLED,
                                        null,
                                        null,
                                        now,
                                        now));
        appendEvent(run, task.id(), "TASK_CANCELLED", "{}");

        List<TaskNode> allTasks = repository.findTasks(run.id(), run.orgId());
        boolean allChildrenSettled =
                allTasks.stream()
                        .filter(candidate -> candidate.parentId() != null)
                        .allMatch(
                                candidate ->
                                        TASK_SUCCEEDED.equals(candidate.status())
                                                || TASK_CANCELLED.equals(candidate.status()));
        boolean coordinatorCompleted =
                allTasks.stream()
                        .filter(candidate -> candidate.parentId() == null)
                        .anyMatch(candidate -> TASK_SUCCEEDED.equals(candidate.status()));
        if (allChildrenSettled && coordinatorCompleted && RUN_RUNNING.equals(run.status())) {
            scheduleCoordinatorContinuation(run, now);
        }
        return true;
    }

    /** True while a continuation has delegated more work that must settle before its final reply. */
    @Transactional(readOnly = true)
    public boolean hasUnsettledChildren(UUID runId) {
        return repository.hasUnsettledChildren(runId);
    }

    /** Persists an execution failure. Terminal states are immutable. */
    @Transactional
    public void markFailed(
            TenantContext tenant,
            UUID agentId,
            UUID runId,
            String failureCode,
            String failureMessage) {
        AssistantRun run = lockOwnedRun(tenant, agentId, runId);
        if (!RUN_RUNNING.equals(run.status())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        repository.completeRun(
                run.id(),
                run.orgId(),
                RUN_FAILED,
                false,
                truncate(failureCode, 128),
                truncate(failureMessage, 2000),
                now,
                now);
        completeExecution(run, TASK_FAILED, ATTEMPT_FAILED, now, failureCode, failureMessage);
        appendEvent(run, null, "RUN_FAILED", "{}");
    }

    /**
     * Records an explicit user cancellation. The caller performs the in-memory agent interrupt after
     * this transaction commits; a disconnect from SSE never calls this method.
     */
    @Transactional
    public Optional<CancelledRun> cancel(TenantContext tenant, UUID agentId, UUID runId) {
        Optional<AssistantRun> maybeRun =
                repository.lockOwnedRun(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId);
        if (maybeRun.isEmpty()) {
            return Optional.empty();
        }
        AssistantRun run = maybeRun.get();
        if (!RUN_RUNNING.equals(run.status())) {
            return Optional.of(new CancelledRun(run.id(), run.agentId(), run.sessionId(), false));
        }
        OffsetDateTime now = OffsetDateTime.now();
        repository.completeRun(run.id(), run.orgId(), RUN_CANCELLED, true, null, null, now, now);
        completeExecution(run, TASK_CANCELLED, ATTEMPT_CANCELLED, now, null, null);
        appendEvent(run, null, "RUN_CANCELLED", "{}");
        return Optional.of(new CancelledRun(run.id(), run.agentId(), run.sessionId(), true));
    }

    @Transactional(readOnly = true)
    public Optional<RunView> getRun(TenantContext tenant, UUID agentId, UUID runId) {
        return repository
                .findOwnedRun(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId)
                .map(this::toView);
    }

    @Transactional(readOnly = true)
    public List<TaskView> getTasks(TenantContext tenant, UUID agentId, UUID runId) {
        AssistantRun run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        return repository.findTasks(run.id(), run.orgId()).stream().map(this::toTaskView).toList();
    }

    @Transactional(readOnly = true)
    public List<AttemptView> getAttempts(TenantContext tenant, UUID agentId, UUID runId) {
        AssistantRun run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        return repository.findAttempts(run.id(), run.orgId()).stream()
                .map(this::toAttemptView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RunEventView> getEvents(
            TenantContext tenant, UUID agentId, UUID runId, long afterSeq, int limit) {
        AssistantRun run =
                getRunEntity(tenant, agentId, runId)
                        .orElseThrow(() -> new RunNotFoundException(runId));
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return repository
                .findEvents(
                        run.id(), run.orgId(), run.userId(), Math.max(0, afterSeq), boundedLimit)
                .stream()
                .map(this::toEventView)
                .toList();
    }

    private AssistantRun lockOwnedRun(TenantContext tenant, UUID agentId, UUID runId) {
        return repository
                .lockOwnedRun(
                        runId,
                        uuid(tenant.orgId(), "orgId"),
                        uuid(tenant.userId(), "userId"),
                        agentId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    private Optional<AssistantRun> getRunEntity(TenantContext tenant, UUID agentId, UUID runId) {
        return repository.findOwnedRun(
                runId, uuid(tenant.orgId(), "orgId"), uuid(tenant.userId(), "userId"), agentId);
    }

    private void completeExecution(
            AssistantRun run,
            String taskStatus,
            String attemptStatus,
            OffsetDateTime completedAt,
            String errorCode,
            String errorMessage) {
        for (TaskNode task : repository.findTasks(run.id(), run.orgId())) {
            if (!isTerminalTaskStatus(task.status())) {
                repository.completeTask(
                        task.id(),
                        run.orgId(),
                        taskStatus,
                        truncate(errorCode, 128),
                        truncate(errorMessage, 2000),
                        completedAt,
                        completedAt);
                appendEvent(run, task.id(), "TASK_" + taskStatus, "{}");
            }
        }
        for (AgentRun agentRun : repository.findAgentRuns(run.id(), run.orgId())) {
            if (RUN_RUNNING.equals(agentRun.status())) {
                repository.updateAgentRunStatus(
                        agentRun.id(), run.orgId(), taskStatus, completedAt, completedAt);
            }
        }
        for (RunAttempt attempt : repository.findAttempts(run.id(), run.orgId())) {
            if (!isTerminalAttemptStatus(attempt.status())) {
                repository.updateAttemptStatus(
                        attempt.id(),
                        run.orgId(),
                        attemptStatus,
                        truncate(errorCode, 128),
                        truncate(errorMessage, 2000),
                        completedAt,
                        completedAt);
            }
        }
    }

    private void completeCoordinator(AssistantRun run, OffsetDateTime completedAt) {
        TaskNode root =
                repository.findTasks(run.id(), run.orgId()).stream()
                        .filter(task -> task.parentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        if (!isTerminalTaskStatus(root.status())) {
            repository.completeTask(
                    root.id(), run.orgId(), TASK_SUCCEEDED, null, null, completedAt, completedAt);
            appendEvent(run, root.id(), "TASK_SUCCEEDED", "{}");
        }
        repository.findAgentRuns(run.id(), run.orgId()).stream()
                .filter(agentRun -> agentRun.parentAgentRunId() == null)
                .filter(agentRun -> RUN_RUNNING.equals(agentRun.status()))
                .forEach(
                        agentRun ->
                                repository.updateAgentRunStatus(
                                        agentRun.id(),
                                        run.orgId(),
                                        TASK_SUCCEEDED,
                                        completedAt,
                                        completedAt));
        repository.findAttempts(run.id(), run.orgId()).stream()
                .filter(attempt -> root.id().equals(attempt.taskId()))
                .filter(attempt -> !isTerminalAttemptStatus(attempt.status()))
                .forEach(
                        attempt ->
                                repository.updateAttemptStatus(
                                        attempt.id(),
                                        run.orgId(),
                                        ATTEMPT_SUCCEEDED,
                                        null,
                                        null,
                                        completedAt,
                                        completedAt));
    }

    private void scheduleCoordinatorContinuation(AssistantRun run, OffsetDateTime now) {
        TaskNode root =
                repository.findTasks(run.id(), run.orgId()).stream()
                        .filter(task -> task.parentId() == null)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Run has no coordinator task"));
        if (!TASK_SUCCEEDED.equals(root.status())) {
            return;
        }
        repository.scheduleTaskContinuation(
                root.id(),
                run.orgId(),
                COORDINATOR_CONTINUATION_INPUT,
                Math.max(root.maxAttempts(), MAX_COORDINATOR_ATTEMPTS),
                now);
        repository.findAgentRuns(run.id(), run.orgId()).stream()
                .filter(agentRun -> agentRun.parentAgentRunId() == null)
                .findFirst()
                .ifPresent(
                        agentRun ->
                                repository.updateAgentRunStatus(
                                        agentRun.id(), run.orgId(), "READY", null, now));
        repository.reopenRun(run.id(), run.orgId(), now);
        appendEvent(run, root.id(), "COORDINATOR_CONTINUATION_READY", "{}");
    }

    private void appendEvent(AssistantRun run, UUID taskId, String eventType, String payloadJson) {
        UUID eventId = UUID.randomUUID();
        long sequence = repository.nextEventSequence(run.id(), run.orgId(), OffsetDateTime.now());
        repository.insertEvent(
                new NewEvent(
                        eventId,
                        run.orgId(),
                        run.userId(),
                        run.id(),
                        taskId,
                        sequence,
                        eventType,
                        payloadJson));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", run.id().toString());
        envelope.put("seq", sequence);
        envelope.put("taskId", taskId != null ? taskId.toString() : null);
        envelope.put("payload", JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class));
        repository.insertOutbox(
                new NewOutboxMessage(
                        UUID.randomUUID(),
                        run.orgId(),
                        run.id(),
                        "assistant_run",
                        eventType,
                        JsonUtils.getJsonCodec().toJson(envelope)));
    }

    private RunView toView(AssistantRun run) {
        return new RunView(
                run.id(),
                run.sessionId(),
                run.agentId(),
                run.mode(),
                run.status(),
                run.cancelRequested(),
                run.failureCode(),
                run.failureMessage(),
                run.tokenBudget(),
                run.consumedTokens(),
                run.costBudgetMicros(),
                run.consumedCostMicros(),
                run.modelCallBudget(),
                run.consumedModelCalls(),
                run.deadlineAt(),
                run.createdAt(),
                run.startedAt(),
                run.completedAt());
    }

    private TaskView toTaskView(TaskNode task) {
        return new TaskView(
                task.id(),
                task.parentId(),
                task.title(),
                task.taskType(),
                task.status(),
                task.workspaceMode(),
                task.tokenBudget(),
                task.consumedTokens(),
                task.costBudgetMicros(),
                task.consumedCostMicros(),
                task.modelCallBudget(),
                task.consumedModelCalls(),
                task.deadlineAt(),
                task.createdAt(),
                task.completedAt());
    }

    private RunEventView toEventView(RunEvent event) {
        return new RunEventView(
                event.sequence(),
                event.eventType(),
                event.taskId(),
                event.payloadJson(),
                event.createdAt());
    }

    private AttemptView toAttemptView(RunAttempt attempt) {
        return new AttemptView(
                attempt.id(),
                attempt.taskId(),
                attempt.agentRunId(),
                attempt.attemptNo(),
                attempt.status(),
                attempt.errorCode(),
                attempt.errorMessage(),
                attempt.startedAt(),
                attempt.completedAt());
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

    private static Long positiveLimit(long value) {
        return value > 0 ? value : null;
    }

    private static Integer positiveLimit(int value) {
        return value > 0 ? value : null;
    }

    private static OffsetDateTime deadline(OffsetDateTime now, long timeoutSeconds) {
        return timeoutSeconds > 0 ? now.plusSeconds(timeoutSeconds) : null;
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
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

    public record SubagentPolicy(
            int maxDepth,
            int maxChildrenPerAgent,
            int maxTasksPerRun,
            long taskTokenBudget,
            long taskCostBudgetMicros,
            int taskModelCallBudget,
            long taskTimeoutSeconds) {

        public SubagentPolicy(int maxDepth, int maxChildrenPerAgent, int maxTasksPerRun) {
            this(maxDepth, maxChildrenPerAgent, maxTasksPerRun, 0, 0, 0, 0);
        }

        public SubagentPolicy {
            if (maxDepth < 1 || maxChildrenPerAgent < 1 || maxTasksPerRun < 1) {
                throw new IllegalArgumentException("Subagent policy limits must be positive");
            }
            if (taskTokenBudget < 0
                    || taskCostBudgetMicros < 0
                    || taskModelCallBudget < 0
                    || taskTimeoutSeconds < 0) {
                throw new IllegalArgumentException("Subagent resource limits cannot be negative");
            }
        }
    }

    public record RunPolicy(
            long runTokenBudget,
            long runCostBudgetMicros,
            int runModelCallBudget,
            long runTimeoutSeconds,
            long taskTokenBudget,
            long taskCostBudgetMicros,
            int taskModelCallBudget,
            long taskTimeoutSeconds,
            String permissionSnapshotJson) {

        public RunPolicy {
            if (runTokenBudget < 0
                    || runCostBudgetMicros < 0
                    || runModelCallBudget < 0
                    || runTimeoutSeconds < 0
                    || taskTokenBudget < 0
                    || taskCostBudgetMicros < 0
                    || taskModelCallBudget < 0
                    || taskTimeoutSeconds < 0) {
                throw new IllegalArgumentException("Run resource limits cannot be negative");
            }
            permissionSnapshotJson =
                    permissionSnapshotJson == null || permissionSnapshotJson.isBlank()
                            ? "{}"
                            : permissionSnapshotJson;
        }

        public static RunPolicy unlimited() {
            return new RunPolicy(0, 0, 0, 0, 0, 0, 0, 0, "{}");
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
            Long tokenBudget,
            long consumedTokens,
            Long costBudgetMicros,
            long consumedCostMicros,
            Integer modelCallBudget,
            int consumedModelCalls,
            OffsetDateTime deadlineAt,
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
            Long tokenBudget,
            long consumedTokens,
            Long costBudgetMicros,
            long consumedCostMicros,
            Integer modelCallBudget,
            int consumedModelCalls,
            OffsetDateTime deadlineAt,
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
