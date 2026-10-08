/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;

/** Framework-neutral routing and governance for model requests outside the reasoning loop. */
public interface PurposeBindableModel extends StepBindableModel {
    enum Purpose {
        REASONING,
        COMPACTION,
        MEMORY_EXTRACT,
        MEMORY_CONSOLIDATE,
        VERIFY
    }

    Model bindToPurpose(RuntimeContext context, Purpose purpose);

    /** The adapter owns durable call and token accounting, including unsuccessful calls. */
    default boolean managesUsage() {
        return false;
    }

    record InvocationLimits(
            int inputTokens, int outputTokens, Duration timeout, String policyVersion) {
        public InvocationLimits {
            if (inputTokens < 1
                    || outputTokens < 1
                    || timeout == null
                    || timeout.isNegative()
                    || timeout.isZero()
                    || policyVersion == null
                    || policyVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "Positive invocation limits and a policy version are required");
            }
        }
    }

    interface BoundInvocation
            extends StepBindableModel.BoundModel, ContextWindowAwareModel, InputTokenAwareModel {
        InvocationLimits invocationLimits();
    }
}
