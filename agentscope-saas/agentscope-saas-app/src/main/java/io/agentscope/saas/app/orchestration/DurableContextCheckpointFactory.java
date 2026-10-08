/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.agent.RuntimeContext;
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
        return create(orgId, runId, agentRunId, null, null);
    }

    public ContextCheckpointStore create(
            UUID orgId, UUID runId, UUID agentRunId, RuntimeContext context, String label) {
        var binding = context == null ? null : new CheckpointTailCodec.Binding(context, label);
        return new ContextCheckpointStore() {
            @Override
            public java.util.List<io.agentscope.core.message.Msg> retainedWindow(
                    java.util.List<io.agentscope.core.message.Msg> workingWindow) {
                return binding != null && service.usesLightweightCheckpoints()
                        ? java.util.List.copyOf(workingWindow)
                        : ContextCheckpointStore.super.retainedWindow(workingWindow);
            }

            @Override
            public StoredCheckpoint save(Draft draft) {
                if (agentRunId != null
                        && !agentRunId.toString().equals(draft.identity().agentRunId()))
                    throw new IllegalStateException("CHECKPOINT_SCOPE_MISMATCH");
                return service.save(orgId, runId, draft, binding);
            }

            @Override
            public java.util.Optional<RecoveryCheckpoint> latest() {
                return agentRunId == null
                        ? java.util.Optional.empty()
                        : service.latestRecovery(orgId, runId, agentRunId, binding);
            }
        };
    }
}
