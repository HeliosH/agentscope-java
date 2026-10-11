/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunRecoveryStatusService {
    private final WorkerPresenceRepository repository;
    private final RecoveryCapability capability;
    private final RunOrchestrationService runs;
    private final SaasProperties properties;
    private RunExecutionPolicyService executionPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExecutionPolicies(RunExecutionPolicyService policies) {
        executionPolicies = policies;
    }

    public RunRecoveryStatusService(
            WorkerPresenceRepository repository,
            RecoveryCapability capability,
            RunOrchestrationService runs,
            SaasProperties properties) {
        this.repository = repository;
        this.capability = capability;
        this.runs = runs;
        this.properties = properties;
    }

    public View status(TenantContext tenant, UUID agent, UUID run) {
        var owned =
                runs.getRun(tenant, agent, run)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Run not found"));
        var queue =
                repository
                        .ownedRecovery(
                                UUID.fromString(tenant.orgId()),
                                UUID.fromString(tenant.userId()),
                                agent,
                                run)
                        .orElse(null);
        if (queue == null)
            return new View(run, owned.status(), "NOT_RECOVERING", 0, 0, null, null, false, 0);
        boolean terminal = !"RUNNING".equals(owned.status()) || owned.cancelRequested();
        String state =
                terminal
                        ? owned.status()
                        : "MANUAL_ACTION".equals(queue.taskStatus())
                                ? "MANUAL_ACTION"
                                : "ARCHIVING".equals(queue.taskStatus())
                                        ? "ARCHIVING"
                                        : !"READY".equals(queue.taskStatus())
                                                ? "RECOVERING"
                                                : consumerState(tenant, run);
        if ("AVAILABLE".equals(state)) state = "SCHEDULED";
        if ("REQUEST_ONLY".equals(state) || "CONSUMER_NOT_REGISTERED".equals(state))
            state = "WAITING_FOR_WORKER";
        long waiting =
                !terminal && "READY".equals(queue.taskStatus()) && queue.queuedAt() != null
                        ? Math.max(
                                0,
                                Duration.between(queue.queuedAt(), OffsetDateTime.now())
                                        .getSeconds())
                        : 0;
        return new View(
                run,
                owned.status(),
                state,
                queue.recoveryCount(),
                queue.attemptCount(),
                queue.reason(),
                queue.nextRecoveryAt(),
                waiting >= properties.getOrchestration().getRecoveryQueueSlaSeconds(),
                waiting);
    }

    public record View(
            UUID runId,
            String runStatus,
            String state,
            int recoveryCount,
            int attemptCount,
            String reasonCode,
            OffsetDateTime nextRecoveryAt,
            boolean queueSlaExceeded,
            long queueWaitSeconds) {}

    private String consumerState(TenantContext tenant, UUID run) {
        try {
            return acceptedConsumerState(tenant, run);
        } catch (
                io.agentscope.saas.domain.orchestration.RunExecutionPolicyUnavailableException
                        missing) {
            return "MANUAL_ACTION";
        }
    }

    private String acceptedConsumerState(TenantContext tenant, UUID run) {
        var accepted =
                executionPolicies == null
                        ? java.util.Optional
                                .<io.agentscope.saas.domain.orchestration.RunExecutionPolicy>empty()
                        : executionPolicies.find(UUID.fromString(tenant.orgId()), run);
        if (accepted.isEmpty()) return capability.snapshot().state();
        var p = accepted.orElseThrow();
        return capability.snapshot(p).state();
    }
}
