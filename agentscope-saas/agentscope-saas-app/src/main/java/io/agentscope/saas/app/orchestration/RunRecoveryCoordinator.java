/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelStreamInterruptedException;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.saas.app.config.SaasProperties;
import org.springframework.stereotype.Component;

/** Shared recovery classification and backoff policy for HTTP chat and durable workers. */
@Component
public class RunRecoveryCoordinator {

    private final SaasProperties.ModelStreamRecovery policy;
    private final RecoveryCapability capability;
    private io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository invocations;
    private RunExecutionPolicyService executionPolicies;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExecutionPolicies(RunExecutionPolicyService policies) {
        executionPolicies = policies;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureInvocationLedger(
            io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository invocations) {
        this.invocations = invocations;
    }

    public RunRecoveryCoordinator(SaasProperties properties) {
        this(properties, new RecoveryCapability(properties, null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunRecoveryCoordinator(SaasProperties properties, RecoveryCapability capability) {
        this.policy =
                properties != null && properties.getModel() != null
                        ? properties.getModel().getStreamRecovery()
                        : new SaasProperties.ModelStreamRecovery();
        this.capability = capability;
    }

    public boolean canScheduleDurableRecovery() {
        return capability.durableConfigured() && capability.snapshot().canEnqueue();
    }

    public boolean canScheduleDurableRecovery(io.agentscope.core.agent.RuntimeContext context) {
        var accepted =
                context == null
                        ? null
                        : context.get(
                                io.agentscope.saas.domain.orchestration.RunExecutionPolicy.class);
        if (accepted != null) return capability.snapshot(accepted).canEnqueue();
        var effective =
                context == null || executionPolicies == null
                        ? policy
                        : policyFor(
                                io.agentscope.saas.app.model.ModelInvocationService.scope(context));
        return effective.isEnabled()
                && effective.getMode() == SaasProperties.ModelStreamRecovery.Mode.DURABLE
                && capability.snapshot(true).canEnqueue();
    }

    public RecoveryCapability.Snapshot consumerStatus() {
        return capability.snapshot();
    }

    RecoveryCapability capability() {
        return capability;
    }

    public Decision decide(
            Throwable error, int currentAttempt, io.agentscope.core.agent.RuntimeContext context) {
        if (context == null) return decide(error, currentAttempt);
        return decide(
                error,
                currentAttempt,
                io.agentscope.saas.app.model.ModelInvocationService.scope(context));
    }

    public Decision decide(
            Throwable error,
            int currentAttempt,
            io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope scope) {
        var effective = policyFor(scope);
        if (effective.isEnabled()
                && invocations != null
                && scope != null
                && scope.runId() != null
                && scope.taskId() != null
                && !isResultSubmissionBoundary(error)
                && classify(error) != null) {
            long failures = invocations.failedReasoningCalls(scope);
            // Task attempt numbers also include successful coordinator continuations.
            if (failures > 0) currentAttempt = (int) Math.min(Integer.MAX_VALUE, failures);
        }
        return decide(error, currentAttempt, effective);
    }

    private SaasProperties.ModelStreamRecovery policyFor(
            io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope scope) {
        return executionPolicies == null
                        || scope == null
                        || scope.orgId() == null
                        || scope.runId() == null
                ? policy
                : executionPolicies.recoveryPolicy(scope.orgId(), scope.runId());
    }

    public Decision decide(Throwable error, int currentAttempt) {
        return decide(error, currentAttempt, policy);
    }

    private Decision decide(
            Throwable error, int currentAttempt, SaasProperties.ModelStreamRecovery policy) {
        if (isResultSubmissionBoundary(error)) return Decision.notRecoverable();
        Failure failure = classify(error);
        int maxAttempts = Math.max(1, policy.getMaxAttempts());
        if (!policy.isEnabled() || failure == null) {
            return Decision.notRecoverable();
        }
        if (currentAttempt >= maxAttempts) {
            return new Decision(
                    false,
                    true,
                    failure.code(),
                    failure.message(),
                    currentAttempt,
                    maxAttempts,
                    0L);
        }
        int nextAttempt = currentAttempt + 1;
        return new Decision(
                true,
                false,
                failure.code(),
                failure.message(),
                nextAttempt,
                maxAttempts,
                recoveryDelayMillis(currentAttempt, policy));
    }

    private boolean isResultSubmissionBoundary(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof io.agentscope.saas.app.workspace.WorkspaceArchiveRejectedException
                    || error
                            instanceof
                            io.agentscope.saas.app.workspace.WorkspaceArchiveDeferredException
                    || error instanceof io.agentscope.saas.orchestration.TaskResultCommitException)
                return true;
            if (error.getCause() == error) break;
        }
        return false;
    }

    private Failure classify(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current
                            instanceof
                            io.agentscope.saas.app.workspace.WorkspaceArchiveRejectedException
                    || current
                            instanceof
                            io.agentscope.saas.app.workspace.WorkspaceArchiveDeferredException)
                return null;
            if (current instanceof ModelStreamInterruptedException) {
                if (hasPermanentClientError(current)) return null;
                return new Failure(
                        "MODEL_STREAM_INTERRUPTED", "Model stream ended after partial output");
            }
            if (current instanceof ModelException) {
                if (hasPermanentClientError(current)) {
                    return null;
                }
                return new Failure("MODEL_REQUEST_FAILED", message(current));
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean hasPermanentClientError(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            Integer status = null;
            if (current instanceof OpenAIException openAi) {
                status = openAi.getStatusCode();
            } else if (current instanceof HttpTransportException transport) {
                status = transport.getStatusCode();
            }
            if (status != null
                    && status >= 400
                    && status < 500
                    && status != 408
                    && status != 409
                    && status != 425
                    && status != 429) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private long recoveryDelayMillis(int failedAttempt, SaasProperties.ModelStreamRecovery policy) {
        long initial = Math.max(0L, policy.getInitialBackoffMillis());
        long maximum = Math.max(initial, policy.getMaxBackoffMillis());
        long delay = initial;
        for (int i = 1; i < failedAttempt && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        if (delay <= 0) return 0;
        return Math.min(
                maximum,
                Math.max(
                        1,
                        (long)
                                (delay
                                        * java.util.concurrent.ThreadLocalRandom.current()
                                                .nextDouble(0.75, 1.25))));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private record Failure(String code, String message) {}

    public record Decision(
            boolean recoverable,
            boolean exhausted,
            String reasonCode,
            String message,
            int attempt,
            int maxAttempts,
            long delayMillis) {

        static Decision notRecoverable() {
            return new Decision(false, false, null, null, 0, 0, 0L);
        }
    }
}
