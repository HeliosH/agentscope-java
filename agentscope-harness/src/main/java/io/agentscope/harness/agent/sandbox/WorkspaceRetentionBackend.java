/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A sealed capture in an existing provider resource; retrieval must never create a sandbox. */
public interface WorkspaceRetentionBackend {
    String providerId();

    void seal(Sandbox sandbox, UUID sourceId, Path capture, OffsetDateTime deadline)
            throws Exception;

    void retrieve(String resourceId, UUID sourceId, Path target, long maxBytes) throws Exception;

    void terminate(String resourceId) throws Exception;

    record Hold(String environmentId, UUID sourceId, OffsetDateTime deadline) {
        public Hold {
            if (environmentId == null || environmentId.isBlank())
                throw new IllegalArgumentException("Retention environment is required");
            java.util.Objects.requireNonNull(sourceId);
            java.util.Objects.requireNonNull(deadline);
        }

        public boolean appliesTo(Sandbox sandbox) {
            return sandbox != null
                    && sandbox.getState() != null
                    && environmentId.equals(sandbox.getState().getSessionId())
                    && deadline.isAfter(OffsetDateTime.now());
        }
    }
}
