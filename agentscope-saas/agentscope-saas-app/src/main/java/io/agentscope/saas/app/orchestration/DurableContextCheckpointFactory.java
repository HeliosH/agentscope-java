/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.tool.ContextCheckpointStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates a tenant/run-scoped context checkpoint port for the core Agent loop. */
@Component
public class DurableContextCheckpointFactory {
    private final DurableContextCheckpointService service;

    public DurableContextCheckpointFactory(DurableContextCheckpointService service) {
        this.service = service;
    }

    public ContextCheckpointStore create(UUID orgId, UUID runId) {
        return create(orgId, runId, null);
    }

    public ContextCheckpointStore create(UUID orgId, UUID runId, UUID agentRunId) {
        return new ContextCheckpointStore() {
            @Override
            public StoredCheckpoint save(Draft draft) {
                return service.save(orgId, runId, draft);
            }

            @Override
            public java.util.Optional<RecoveryCheckpoint> latest() {
                return agentRunId == null
                        ? java.util.Optional.empty()
                        : service.latestRecovery(orgId, runId, agentRunId);
            }
        };
    }
}
