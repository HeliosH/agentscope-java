/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.util.Map;

/** Durable boundary around an executable tool invocation. Implementations must be thread-safe. */
public interface ToolExecutionJournal {

    PrepareResult prepare(Invocation invocation);

    void markRunning(Invocation invocation);

    void complete(
            Invocation invocation, TerminalStatus status, ToolResultBlock result, Throwable error);

    enum PrepareAction {
        EXECUTE,
        REUSE,
        RECONCILE
    }

    enum TerminalStatus {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        SUSPENDED,
        OUTCOME_UNKNOWN
    }

    record PrepareResult(PrepareAction action, ToolResultBlock reusableResult, String message) {
        public PrepareResult {
            if (action == null) throw new IllegalArgumentException("prepare action is required");
            if (action == PrepareAction.REUSE && reusableResult == null) {
                throw new IllegalArgumentException("REUSE requires a prior result");
            }
        }

        public static PrepareResult execute() {
            return new PrepareResult(PrepareAction.EXECUTE, null, null);
        }

        public static PrepareResult reuse(ToolResultBlock result) {
            return new PrepareResult(PrepareAction.REUSE, result, null);
        }

        public static PrepareResult reconcile(String message) {
            return new PrepareResult(PrepareAction.RECONCILE, null, message);
        }
    }

    record Invocation(
            String operationId,
            String invocationId,
            String stepId,
            StepSnapshot.Identity identity,
            ExecutionLeaseSnapshot executionLease,
            String toolCallId,
            String toolName,
            String inputHash,
            Map<String, Object> input,
            ToolRetrySafety retrySafety) {
        public Invocation {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId is required");
            }
            if (invocationId == null || invocationId.isBlank()) {
                throw new IllegalArgumentException("invocationId is required");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId is required");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName is required");
            }
            input = input == null ? Map.of() : Map.copyOf(input);
            retrySafety = retrySafety == null ? ToolRetrySafety.NEVER : retrySafety;
        }

        public Invocation(
                String operationId,
                String invocationId,
                String stepId,
                StepSnapshot.Identity identity,
                String toolCallId,
                String toolName,
                String inputHash,
                Map<String, Object> input,
                ToolRetrySafety retrySafety) {
            this(
                    operationId,
                    invocationId,
                    stepId,
                    identity,
                    null,
                    toolCallId,
                    toolName,
                    inputHash,
                    input,
                    retrySafety);
        }
    }
}
