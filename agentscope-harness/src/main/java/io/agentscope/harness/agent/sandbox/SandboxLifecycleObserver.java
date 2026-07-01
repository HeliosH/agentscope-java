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

/** Observes best-effort sandbox lifecycle events without affecting sandbox cleanup semantics. */
public interface SandboxLifecycleObserver {

    static SandboxLifecycleObserver noop() {
        return Noop.INSTANCE;
    }

    default void onAcquireStartFailure(RuntimeContext runtimeContext, Exception error) {}

    default void onAcquireStartSucceeded(
            RuntimeContext runtimeContext,
            SandboxAcquireResult.AcquisitionSource source,
            long durationNanos) {}

    default void onWorkspaceProjectionSucceeded(
            RuntimeContext runtimeContext, int projectedFiles) {}

    default void onWorkspaceProjectionFailed(RuntimeContext runtimeContext, Exception error) {}

    default void onStatePersistFailed(RuntimeContext runtimeContext, Exception error) {}

    default void onSandboxStopFailed(RuntimeContext runtimeContext, Exception error) {}

    default void onSandboxShutdownFailed(RuntimeContext runtimeContext, Exception error) {}

    default void onBackendReleaseFailed(RuntimeContext runtimeContext, Exception error) {}

    enum Noop implements SandboxLifecycleObserver {
        INSTANCE
    }
}
