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

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService.TaskLease;
import io.agentscope.saas.orchestration.DurableTaskExecutor;
import io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims durable leases and executes them independently of browser or request lifetimes. */
@Component
public class DurableTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DurableTaskWorker.class);

    private final DurableTaskLeaseService leases;
    private final DurableTaskExecutor taskExecutor;
    private final SaasProperties properties;
    private final Executor executor;
    private final String workerId;
    private final Set<UUID> reserved = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Thread> active = new ConcurrentHashMap<>();

    @Autowired
    public DurableTaskWorker(
            DurableTaskLeaseService leases,
            DurableTaskExecutor taskExecutor,
            SaasProperties properties) {
        this(
                leases,
                taskExecutor,
                properties,
                Executors.newFixedThreadPool(
                        Math.max(1, properties.getOrchestration().getWorkerConcurrency()),
                        runnable -> {
                            Thread thread = new Thread(runnable);
                            thread.setName("durable-task-worker-" + UUID.randomUUID());
                            thread.setDaemon(true);
                            return thread;
                        }),
                "worker-" + UUID.randomUUID());
    }

    DurableTaskWorker(
            DurableTaskLeaseService leases,
            DurableTaskExecutor taskExecutor,
            SaasProperties properties,
            Executor executor,
            String workerId) {
        this.leases = leases;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        this.executor = executor;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${saas.orchestration.scheduler-poll-millis:1000}",
            timeUnit = TimeUnit.MILLISECONDS)
    public void pollScheduled() {
        if (!enabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("Durable task polling failed: {}", errorMessage(e));
        }
    }

    @Scheduled(
            fixedDelayString = "${saas.orchestration.scheduler-heartbeat-seconds:20}",
            timeUnit = TimeUnit.SECONDS)
    public void heartbeatScheduled() {
        if (!enabled()) {
            return;
        }
        active.forEach(
                (attemptId, thread) -> {
                    try {
                        if (!leases.heartbeat(attemptId, workerId)) {
                            log.warn(
                                    "Lost durable task lease attempt={} worker={}",
                                    attemptId,
                                    workerId);
                            thread.interrupt();
                        }
                    } catch (RuntimeException e) {
                        log.warn(
                                "Durable task heartbeat failed attempt={}: {}",
                                attemptId,
                                errorMessage(e));
                    }
                });
    }

    int pollOnce() {
        int concurrency = Math.max(1, properties.getOrchestration().getWorkerConcurrency());
        int available = Math.max(0, concurrency - reserved.size());
        if (available == 0) {
            return 0;
        }
        int limit =
                Math.min(
                        available,
                        Math.max(1, properties.getOrchestration().getSchedulerBatchSize()));
        var claimed = leases.claimReady(workerId, limit);
        for (TaskLease lease : claimed) {
            if (!reserved.add(lease.attemptId())) {
                continue;
            }
            try {
                executor.execute(() -> execute(lease));
            } catch (RuntimeException e) {
                reserved.remove(lease.attemptId());
                recordFailure(lease, "WORKER_REJECTED", errorMessage(e));
            }
        }
        return claimed.size();
    }

    private void execute(TaskLease lease) {
        Thread thread = Thread.currentThread();
        if (active.putIfAbsent(lease.attemptId(), thread) != null) {
            reserved.remove(lease.attemptId());
            return;
        }
        try {
            if (!leases.start(lease.attemptId(), workerId)) {
                return;
            }
            var result = taskExecutor.execute(toRequest(lease));
            if (!leases.succeed(lease.attemptId(), workerId, result.outputJson())) {
                log.warn(
                        "Durable task result rejected after lease loss attempt={}",
                        lease.attemptId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(lease, "WORKER_INTERRUPTED", "Worker execution was interrupted");
        } catch (Exception e) {
            recordFailure(lease, "TASK_EXECUTION_FAILED", errorMessage(e));
        } finally {
            active.remove(lease.attemptId(), thread);
            reserved.remove(lease.attemptId());
        }
    }

    private void recordFailure(TaskLease lease, String code, String message) {
        try {
            leases.fail(lease.attemptId(), workerId, code, message);
        } catch (RuntimeException stateError) {
            log.warn(
                    "Unable to persist durable task failure attempt={}: {}",
                    lease.attemptId(),
                    errorMessage(stateError));
        }
    }

    private ExecutionRequest toRequest(TaskLease lease) {
        return new ExecutionRequest(
                lease.attemptId(),
                lease.orgId(),
                lease.runId(),
                lease.taskId(),
                lease.userId(),
                lease.agentId(),
                lease.sessionId(),
                lease.agentRunId(),
                lease.agentType(),
                lease.subSessionId(),
                lease.role(),
                lease.tier(),
                lease.maxSandboxes(),
                lease.tokenQuota(),
                lease.title(),
                lease.inputJson());
    }

    private boolean enabled() {
        return properties.getOrchestration().isEnabled()
                && properties.getOrchestration().isSchedulerEnabled();
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    @PreDestroy
    public void close() {
        active.values().forEach(Thread::interrupt);
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
