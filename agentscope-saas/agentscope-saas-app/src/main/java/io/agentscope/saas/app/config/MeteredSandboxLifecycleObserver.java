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
package io.agentscope.saas.app.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxLifecycleObserver;
import io.agentscope.saas.sandbox.SandboxMetrics;

final class MeteredSandboxLifecycleObserver implements SandboxLifecycleObserver {

    private final String sandboxType;
    private final SandboxMetrics metrics;

    MeteredSandboxLifecycleObserver(String sandboxType, SandboxMetrics metrics) {
        this.sandboxType = sandboxType;
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
    }

    @Override
    public void onAcquireStartFailure(RuntimeContext runtimeContext, Exception error) {
        metrics.acquireStartFailed(sandboxType);
    }

    @Override
    public void onWorkspaceProjectionSucceeded(RuntimeContext runtimeContext, int projectedFiles) {
        if (projectedFiles > 0) {
            metrics.workspaceProjectionSucceeded(sandboxType);
        }
    }

    @Override
    public void onWorkspaceProjectionFailed(RuntimeContext runtimeContext, Exception error) {
        metrics.workspaceProjectionFailed(sandboxType);
    }

    @Override
    public void onStatePersistFailed(RuntimeContext runtimeContext, Exception error) {
        metrics.statePersistFailed(sandboxType);
    }

    @Override
    public void onSandboxStopFailed(RuntimeContext runtimeContext, Exception error) {
        metrics.sandboxStopFailed(sandboxType);
    }

    @Override
    public void onSandboxShutdownFailed(RuntimeContext runtimeContext, Exception error) {
        metrics.sandboxShutdownFailed(sandboxType);
    }

    @Override
    public void onBackendReleaseFailed(RuntimeContext runtimeContext, Exception error) {
        metrics.backendReleaseFailed(sandboxType);
    }
}
