/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * Factory for creating and resuming {@link Sandbox} instances.
 *
 * @param <O> the type of client options for this implementation
 */
public interface SandboxClient<O extends SandboxClientOptions> {

    /** Whether this backend consumes {@link RuntimeContext} during create/resume. */
    default boolean supportsRuntimeContext() {
        return false;
    }

    /**
     * Creates a new sandbox with the given workspace spec and snapshot spec.
     *
     * <p>Returned in a pre-start state; call {@link Sandbox#start()} before use.
     */
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);

    /**
     * Creates a sandbox with access to the current call context.
     *
     * <p>Backends with deployment-managed persistent storage can use the context to resolve an
     * isolation-scoped workspace volume. Existing backends keep the legacy behavior through this
     * default implementation.
     */
    default Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            O options,
            RuntimeContext runtimeContext) {
        return create(workspaceSpec, snapshotSpec, options);
    }

    /**
     * Resumes a sandbox from previously serialized {@link SandboxState}.
     */
    Sandbox resume(SandboxState state);

    /** Context-aware resume counterpart used by persistent-volume backends. */
    default Sandbox resume(SandboxState state, RuntimeContext runtimeContext) {
        return resume(state);
    }

    void delete(Sandbox sandbox);

    String serializeState(SandboxState state);

    SandboxState deserializeState(String json);
}
