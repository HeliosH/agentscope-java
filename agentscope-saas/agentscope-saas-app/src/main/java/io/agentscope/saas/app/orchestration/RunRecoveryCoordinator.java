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
    private final boolean durableRecoveryAvailable;

    public RunRecoveryCoordinator(SaasProperties properties) {
        this.policy =
                properties != null && properties.getModel() != null
                        ? properties.getModel().getStreamRecovery()
                        : new SaasProperties.ModelStreamRecovery();
        boolean workerEnabled =
                properties != null
                        && properties.getOrchestration().isEnabled()
                        && properties.getOrchestration().isSchedulerEnabled();
        this.durableRecoveryAvailable =
                policy.isEnabled()
                        && policy.getMode() == SaasProperties.ModelStreamRecovery.Mode.DURABLE
                        && workerEnabled;
        if (policy.isEnabled()
                && policy.getMode() == SaasProperties.ModelStreamRecovery.Mode.DURABLE
                && !workerEnabled) {
            throw new IllegalArgumentException(
                    "DURABLE model recovery requires orchestration and scheduler enabled");
        }
    }

    public boolean canScheduleDurableRecovery() {
        return durableRecoveryAvailable;
    }

    public Decision decide(Throwable error, int currentAttempt) {
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
                recoveryDelayMillis(currentAttempt));
    }

    private Failure classify(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof ModelStreamInterruptedException) {
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

    private long recoveryDelayMillis(int failedAttempt) {
        long initial = Math.max(0L, policy.getInitialBackoffMillis());
        long maximum = Math.max(initial, policy.getMaxBackoffMillis());
        long delay = initial;
        for (int i = 1; i < failedAttempt && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return delay;
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
