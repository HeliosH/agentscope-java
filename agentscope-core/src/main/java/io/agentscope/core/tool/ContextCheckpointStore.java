/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Optional;

/** Durable port for a recoverable conversation boundary after committed tool results. */
public interface ContextCheckpointStore {

    StoredCheckpoint save(Draft draft);

    /** Returns the latest committed conversation boundary for the current durable Agent Run. */
    default Optional<RecoveryCheckpoint> latest() {
        return Optional.empty();
    }

    record Draft(
            StepSnapshot.Identity identity,
            ExecutionLeaseSnapshot executionLease,
            String stepId,
            String historyHash,
            String summary,
            List<Msg> retainedTail,
            String retainedFactsVersion,
            List<String> pendingOperationIds,
            String workspaceVersion) {
        public Draft {
            if (identity == null)
                throw new IllegalArgumentException("checkpoint identity is required");
            if (stepId == null || stepId.isBlank()) {
                throw new IllegalArgumentException("checkpoint stepId is required");
            }
            if (historyHash == null || historyHash.isBlank()) {
                throw new IllegalArgumentException("checkpoint historyHash is required");
            }
            summary = summary == null ? "" : summary;
            retainedTail = retainedTail == null ? List.of() : List.copyOf(retainedTail);
            pendingOperationIds =
                    pendingOperationIds == null ? List.of() : List.copyOf(pendingOperationIds);
        }
    }

    record StoredCheckpoint(long historyRevision, String historyHash) {
        public StoredCheckpoint {
            if (historyRevision < 1) {
                throw new IllegalArgumentException("historyRevision must be positive");
            }
        }
    }

    record RecoveryCheckpoint(
            long historyRevision,
            String historyHash,
            String summary,
            List<Msg> retainedTail,
            List<String> pendingOperationIds,
            String workspaceVersion) {
        public RecoveryCheckpoint {
            if (historyRevision < 1) {
                throw new IllegalArgumentException("historyRevision must be positive");
            }
            if (historyHash == null || historyHash.isBlank()) {
                throw new IllegalArgumentException("historyHash is required");
            }
            summary = summary == null ? "" : summary;
            retainedTail = retainedTail == null ? List.of() : List.copyOf(retainedTail);
            pendingOperationIds =
                    pendingOperationIds == null ? List.of() : List.copyOf(pendingOperationIds);
        }
    }
}
