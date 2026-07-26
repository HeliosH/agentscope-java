/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.AttemptRef;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.CoordinatorRef;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.ExpiredAttempt;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.NewAttempt;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.NewOutboxEvent;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.NewRunEvent;
import io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository.TaskCandidate;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.CompletionGate;
import io.agentscope.saas.orchestration.DurableTaskExecutor.DependencyContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL-backed task lease state machine used by durable Agent workers. */
@Service
public class DurableTaskLeaseService {

    private static final String RETRY_IDEMPOTENT = "IDEMPOTENT";
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int MAX_COORDINATOR_ATTEMPTS = 33;
    private static final String COORDINATOR_CONTINUATION_INPUT =
            "{\"continuation\":true,\"prompt\":\"Review the completed subagent results and "
                    + "continue the original task. Produce the final answer, or delegate only "
                    + "when additional work is required.\"}";

    private final DurableTaskLeaseRepository repository;
    private final TransactionOperations transactions;
    private final SaasProperties properties;
    private final CompletionGate completionGate = new CompletionGate();

    @Autowired
    public DurableTaskLeaseService(
            DurableTaskLeaseRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            SaasProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Claims dependency-ready nodes. A conditional status update prevents duplicate claims. */
    public List<TaskLease> claimReady(String workerId, int requestedLimit) {
        requireWorker(workerId);
        int limit =
                Math.max(
                        1,
                        Math.min(
                                requestedLimit,
                                Math.max(
                                        1, properties.getOrchestration().getSchedulerBatchSize())));
        OffsetDateTime now = OffsetDateTime.now();
        List<TaskCandidate> candidates = loadReadyCandidates(now, limit * 2);
        List<TaskLease> leases = new ArrayList<>();
        for (TaskCandidate candidate : candidates) {
            if (leases.size() >= limit) {
                break;
            }
            TaskLease lease =
                    transactions.execute(status -> claimCandidate(candidate, workerId, now));
            if (lease != null) {
                leases.add(lease);
            }
        }
        return leases;
    }

    /** Moves a leased Attempt into RUNNING and retains the same lease owner. */
    public boolean start(UUID attemptId, String workerId) {
        requireWorker(workerId);
        Boolean started =
                transactions.execute(
                        status -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            AttemptRef ref = findAttempt(attemptId, workerId);
                            if (ref == null) {
                                return false;
                            }
                            int updated =
                                    repository.startAttempt(
                                            attemptId, workerId, now, leaseExpiry(now));
                            if (updated != 1) {
                                return false;
                            }
                            repository.markTaskRunning(ref.taskId(), now);
                            if (ref.agentRunId() != null) {
                                repository.markAgentRunRunning(ref.agentRunId(), now);
                            }
                            appendEvent(ref, "TASK_STARTED", payload(attemptId, workerId, null));
                            return true;
                        });
        return Boolean.TRUE.equals(started);
    }

    /** Extends a live Attempt lease. False means ownership was lost or the lease expired. */
    public boolean heartbeat(UUID attemptId, String workerId) {
        requireWorker(workerId);
        OffsetDateTime now = OffsetDateTime.now();
        return repository.heartbeat(attemptId, workerId, now, leaseExpiry(now)) == 1;
    }

    public boolean succeed(UUID attemptId, String workerId) {
        return succeed(attemptId, workerId, "{}");
    }

    public boolean succeed(UUID attemptId, String workerId, String outputJson) {
        return finish(attemptId, workerId, "SUCCEEDED", null, null, outputJson, false);
    }

    public boolean fail(UUID attemptId, String workerId, String errorCode, String errorMessage) {
        return finish(attemptId, workerId, "FAILED", errorCode, errorMessage, null, false);
    }

    /** Reclaims Attempts whose worker stopped heartbeating. Retries always use a new Attempt row. */
    public int recoverExpired(int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        OffsetDateTime now = OffsetDateTime.now();
        List<ExpiredAttempt> expired = repository.findExpiredAttempts(now, limit);
        int recovered = 0;
        for (ExpiredAttempt attempt : expired) {
            if (finish(
                    attempt.attemptId(),
                    attempt.workerId(),
                    "ABANDONED",
                    "WORKER_LEASE_EXPIRED",
                    "Worker heartbeat lease expired",
                    null,
                    true)) {
                recovered++;
            }
        }
        return recovered;
    }

    private List<TaskCandidate> loadReadyCandidates(OffsetDateTime now, int limit) {
        return repository.findReadyCandidates(now, limit);
    }

    private TaskLease claimCandidate(TaskCandidate candidate, String workerId, OffsetDateTime now) {
        int attemptNo = candidate.lastAttemptNo() + 1;
        if (attemptNo > candidate.maxAttempts()) {
            return null;
        }
        int claimed = repository.claimTask(candidate.taskId(), now);
        if (claimed != 1) {
            return null;
        }
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime expiresAt = leaseExpiry(now);
        repository.createAttempt(
                new NewAttempt(
                        attemptId,
                        candidate.orgId(),
                        candidate.runId(),
                        candidate.taskId(),
                        candidate.agentRunId(),
                        attemptNo,
                        workerId,
                        expiresAt,
                        now,
                        "task:" + candidate.taskId() + ":attempt:" + attemptNo));
        AttemptRef ref =
                new AttemptRef(
                        attemptId,
                        candidate.orgId(),
                        candidate.runId(),
                        candidate.taskId(),
                        candidate.agentRunId(),
                        attemptNo,
                        candidate.maxAttempts(),
                        candidate.retryMode(),
                        candidate.retryBaseSeconds(),
                        candidate.expectedOutputJson(),
                        candidate.acceptanceJson());
        appendEvent(ref, "TASK_CLAIMED", payload(attemptId, workerId, attemptNo));
        List<DependencyContext> dependencies =
                repository.findCompletedDependencies(candidate.taskId()).stream()
                        .map(
                                dependency ->
                                        new DependencyContext(
                                                dependency.taskId(),
                                                dependency.title(),
                                                dependency.outputJson(),
                                                dependency.artifactRefs().stream()
                                                        .map(
                                                                artifact ->
                                                                        "file-version://"
                                                                                + artifact
                                                                                        .fileVersionId())
                                                        .toList()))
                        .toList();
        return new TaskLease(
                attemptId,
                candidate.orgId(),
                candidate.runId(),
                candidate.userId(),
                candidate.agentId(),
                candidate.sessionId(),
                candidate.agentRunId(),
                candidate.agentType(),
                candidate.subSessionId(),
                candidate.role(),
                candidate.tier(),
                candidate.maxSandboxes(),
                candidate.tokenQuota(),
                candidate.taskId(),
                attemptNo,
                workerId,
                expiresAt,
                candidate.title(),
                candidate.inputJson(),
                candidate.workspaceIsolationMode(),
                candidate.expectedOutputJson(),
                candidate.acceptanceJson(),
                dependencies);
    }

    private boolean finish(
            UUID attemptId,
            String workerId,
            String attemptStatus,
            String errorCode,
            String errorMessage,
            String outputJson,
            boolean requireExpired) {
        requireWorker(workerId);
        Boolean finished =
                transactions.execute(
                        status -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            AttemptRef ref = findAttempt(attemptId, workerId);
                            if (ref == null) {
                                return false;
                            }
                            String finalStatus = attemptStatus;
                            String finalErrorCode = errorCode;
                            String finalErrorMessage = errorMessage;
                            String normalizedOutput =
                                    "SUCCEEDED".equals(attemptStatus)
                                            ? normalizeJson(outputJson)
                                            : null;
                            if ("SUCCEEDED".equals(attemptStatus)) {
                                CompletionGate.Decision decision =
                                        completionGate.evaluate(
                                                ref.expectedOutputJson(),
                                                ref.acceptanceJson(),
                                                normalizedOutput);
                                if (decision.verificationRequired()) {
                                    appendEvent(ref, "VERIFICATION_STARTED", "{}");
                                }
                                if (!decision.passed()) {
                                    finalStatus = "FAILED";
                                    finalErrorCode = CompletionGate.ERROR_CODE;
                                    finalErrorMessage = String.join("; ", decision.failures());
                                    appendEvent(
                                            ref,
                                            "VERIFICATION_FAILED",
                                            verificationPayload(decision));
                                } else if (decision.verificationRequired()) {
                                    appendEvent(
                                            ref,
                                            "VERIFICATION_PASSED",
                                            verificationPayload(decision));
                                }
                            }
                            int updated =
                                    repository.finishAttempt(
                                            attemptId,
                                            workerId,
                                            finalStatus,
                                            truncate(finalErrorCode, 128),
                                            truncate(finalErrorMessage, MAX_ERROR_LENGTH),
                                            now,
                                            requireExpired ? now : null);
                            if (updated != 1) {
                                return false;
                            }
                            appendEvent(
                                    ref,
                                    "ATTEMPT_" + finalStatus,
                                    payload(attemptId, workerId, ref.attemptNo()));
                            if ("SUCCEEDED".equals(finalStatus)) {
                                completeTask(ref, now, normalizedOutput);
                            } else {
                                retryOrStopTask(ref, now, finalErrorCode, finalErrorMessage);
                            }
                            return true;
                        });
        return Boolean.TRUE.equals(finished);
    }

    private void completeTask(AttemptRef ref, OffsetDateTime now, String outputJson) {
        boolean coordinator = isCoordinatorTask(ref.taskId());
        repository.completeTask(ref.taskId(), now, now, outputJson);
        updateAgentRun(ref.agentRunId(), "SUCCEEDED", now);
        appendEvent(ref, "TASK_SUCCEEDED", "{}");
        repository.releaseReadyDependencies(ref.runId(), now);
        if (!coordinator) {
            scheduleCoordinatorContinuation(ref, now);
            return;
        }
        int runCompleted = repository.completeRunIfAllTasksTerminal(ref.runId(), now);
        if (runCompleted == 1) {
            appendEvent(ref, "RUN_SUCCEEDED", "{}");
        } else {
            appendEvent(ref, "COORDINATOR_SUCCEEDED", "{}");
        }
    }

    private boolean isCoordinatorTask(UUID taskId) {
        return repository.isCoordinatorTask(taskId);
    }

    private void scheduleCoordinatorContinuation(AttemptRef ref, OffsetDateTime now) {
        CoordinatorRef coordinator = repository.findCompletedCoordinator(ref.runId()).orElse(null);
        if (coordinator == null) {
            return;
        }
        int scheduled =
                repository.scheduleCoordinatorContinuation(
                        coordinator.taskId(),
                        COORDINATOR_CONTINUATION_INPUT,
                        MAX_COORDINATOR_ATTEMPTS,
                        now);
        if (scheduled != 1) {
            return;
        }
        if (coordinator.agentRunId() != null) {
            repository.resetAgentRun(coordinator.agentRunId(), now);
        }
        appendEvent(
                ref,
                coordinator.taskId(),
                coordinator.agentRunId(),
                null,
                "COORDINATOR_CONTINUATION_READY",
                "{}");
    }

    private void retryOrStopTask(
            AttemptRef ref, OffsetDateTime now, String errorCode, String errorMessage) {
        boolean retryable = RETRY_IDEMPOTENT.equals(ref.retryMode());
        boolean hasAttempts = ref.attemptNo() < ref.maxAttempts();
        if (retryable && hasAttempts) {
            OffsetDateTime next = now.plusSeconds(retryDelay(ref));
            repository.scheduleTaskRetry(
                    ref.taskId(),
                    next,
                    now,
                    truncate(errorCode, 128),
                    truncate(errorMessage, MAX_ERROR_LENGTH));
            updateAgentRun(ref.agentRunId(), "READY", now);
            appendEvent(ref, "TASK_RETRY_SCHEDULED", payload(null, null, ref.attemptNo() + 1));
            return;
        }
        String taskStatus = retryable ? "FAILED" : "MANUAL_ACTION";
        repository.stopTask(
                ref.taskId(),
                taskStatus,
                now,
                truncate(errorCode, 128),
                truncate(errorMessage, MAX_ERROR_LENGTH));
        updateAgentRun(ref.agentRunId(), taskStatus, now);
        appendEvent(ref, "TASK_" + taskStatus, "{}");
        if ("FAILED".equals(taskStatus)) {
            int runFailed =
                    repository.failRun(
                            ref.runId(),
                            truncate(errorCode, 128),
                            truncate(errorMessage, MAX_ERROR_LENGTH),
                            now);
            if (runFailed == 1) {
                repository.cancelSiblingTasks(ref.runId(), ref.taskId(), now);
                repository.cancelSiblingAttempts(ref.runId(), ref.taskId(), now);
                repository.cancelSiblingAgentRuns(ref.runId(), ref.taskId(), now);
                appendEvent(ref, "RUN_FAILED", "{}");
            }
        }
    }

    private AttemptRef findAttempt(UUID attemptId, String workerId) {
        return repository.findAttempt(attemptId, workerId).orElse(null);
    }

    private void appendEvent(AttemptRef ref, String eventType, String payloadJson) {
        appendEvent(ref, ref.taskId(), ref.agentRunId(), ref.attemptId(), eventType, payloadJson);
    }

    private void appendEvent(
            AttemptRef ref,
            UUID taskId,
            UUID agentRunId,
            UUID attemptId,
            String eventType,
            String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now();
        long seq = repository.nextEventSequence(ref.runId(), now);
        UUID eventId = UUID.randomUUID();
        repository.appendRunEvent(
                new NewRunEvent(
                        eventId,
                        ref.orgId(),
                        ref.runId(),
                        taskId,
                        agentRunId,
                        attemptId,
                        seq,
                        eventType,
                        payloadJson));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("runId", ref.runId().toString());
        envelope.put("seq", seq);
        envelope.put("taskId", taskId != null ? taskId.toString() : null);
        envelope.put("payload", JsonUtils.getJsonCodec().fromJson(payloadJson, Object.class));
        repository.appendOutbox(
                new NewOutboxEvent(
                        UUID.randomUUID(),
                        ref.orgId(),
                        ref.runId(),
                        eventType,
                        JsonUtils.getJsonCodec().toJson(envelope)));
    }

    private long retryDelay(AttemptRef ref) {
        long maximum = Math.max(1L, properties.getOrchestration().getSchedulerRetryMaxSeconds());
        long delay = Math.max(1L, ref.retryBaseSeconds());
        for (int i = 1; i < ref.attemptNo() && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return delay;
    }

    private void updateAgentRun(UUID agentRunId, String status, OffsetDateTime now) {
        if (agentRunId == null) {
            return;
        }
        boolean terminal =
                "SUCCEEDED".equals(status)
                        || "FAILED".equals(status)
                        || "MANUAL_ACTION".equals(status)
                        || "CANCELLED".equals(status);
        repository.updateAgentRun(agentRunId, status, now, terminal ? now : null);
    }

    private OffsetDateTime leaseExpiry(OffsetDateTime now) {
        return now.plusSeconds(
                Math.max(1L, properties.getOrchestration().getSchedulerLeaseSeconds()));
    }

    private static String payload(UUID attemptId, String workerId, Integer attemptNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attemptId != null ? attemptId.toString() : null);
        payload.put("workerId", workerId);
        payload.put("attemptNo", attemptNo);
        return JsonUtils.getJsonCodec().toJson(payload);
    }

    private static void requireWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalArgumentException("workerId must contain 1-255 characters");
        }
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    private static String normalizeJson(String value) {
        String normalized = value == null || value.isBlank() ? "{}" : value;
        JsonUtils.getJsonCodec().fromJson(normalized, Object.class);
        return normalized;
    }

    private static String verificationPayload(CompletionGate.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", decision.passed());
        payload.put("required", decision.verificationRequired());
        payload.put("evidenceCount", decision.evidenceCount());
        payload.put("artifactCount", decision.artifactCount());
        payload.put("failures", decision.failures());
        return JsonUtils.getJsonCodec().toJson(payload);
    }

    public record TaskLease(
            UUID attemptId,
            UUID orgId,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            UUID agentRunId,
            String agentType,
            String subSessionId,
            String role,
            String tier,
            int maxSandboxes,
            long tokenQuota,
            UUID taskId,
            int attemptNo,
            String workerId,
            OffsetDateTime leaseExpiresAt,
            String title,
            String inputJson,
            WorkspaceIsolationMode workspaceIsolationMode,
            String expectedOutputJson,
            String acceptanceJson,
            List<DependencyContext> dependencies) {

        public TaskLease {
            workspaceIsolationMode =
                    workspaceIsolationMode != null
                            ? workspaceIsolationMode
                            : WorkspaceIsolationMode.NONE;
            expectedOutputJson =
                    expectedOutputJson == null || expectedOutputJson.isBlank()
                            ? "[]"
                            : expectedOutputJson;
            acceptanceJson =
                    acceptanceJson == null || acceptanceJson.isBlank() ? "[]" : acceptanceJson;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public TaskLease(
                UUID attemptId,
                UUID orgId,
                UUID runId,
                UUID userId,
                UUID agentId,
                UUID sessionId,
                UUID agentRunId,
                String agentType,
                String subSessionId,
                String role,
                String tier,
                int maxSandboxes,
                long tokenQuota,
                UUID taskId,
                int attemptNo,
                String workerId,
                OffsetDateTime leaseExpiresAt,
                String title,
                String inputJson,
                WorkspaceIsolationMode workspaceIsolationMode) {
            this(
                    attemptId,
                    orgId,
                    runId,
                    userId,
                    agentId,
                    sessionId,
                    agentRunId,
                    agentType,
                    subSessionId,
                    role,
                    tier,
                    maxSandboxes,
                    tokenQuota,
                    taskId,
                    attemptNo,
                    workerId,
                    leaseExpiresAt,
                    title,
                    inputJson,
                    workspaceIsolationMode,
                    "[]",
                    "[]",
                    List.of());
        }
    }
}
