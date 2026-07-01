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
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult.AcquisitionSource;
import io.agentscope.harness.agent.sandbox.SandboxLifecycleObserver;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.SandboxExternalIds;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.agentscope.saas.sandbox.SandboxTrackingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MeteredSandboxLifecycleObserver implements SandboxLifecycleObserver {

    private static final Logger log =
            LoggerFactory.getLogger(MeteredSandboxLifecycleObserver.class);

    private final String sandboxType;
    private final SandboxMetrics metrics;
    private final SandboxBroker broker;

    MeteredSandboxLifecycleObserver(String sandboxType, SandboxMetrics metrics) {
        this(sandboxType, metrics, null);
    }

    MeteredSandboxLifecycleObserver(
            String sandboxType, SandboxMetrics metrics, SandboxBroker broker) {
        this.sandboxType = sandboxType;
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
        this.broker = broker;
    }

    @Override
    public void onAcquireStartFailure(RuntimeContext runtimeContext, Exception error) {
        metrics.acquireStartFailed(sandboxType);
    }

    @Override
    public void onAcquireStartSucceeded(
            RuntimeContext runtimeContext, AcquisitionSource source, long durationNanos) {
        metrics.recordAcquireStart(
                sandboxType, source != null ? source.metricTag() : null, durationNanos);
        updateExternalId(runtimeContext);
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

    private void updateExternalId(RuntimeContext runtimeContext) {
        if (broker == null || runtimeContext == null) {
            return;
        }
        SandboxTrackingContext tracking = runtimeContext.get(SandboxTrackingContext.class);
        if (tracking == null || tracking.trackingId() == null || tracking.orgId() == null) {
            return;
        }
        SandboxExternalIds.fromRuntimeContext(runtimeContext)
                .ifPresent(externalId -> updateExternalId(tracking, externalId));
    }

    private void updateExternalId(SandboxTrackingContext tracking, String externalId) {
        String previous = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(tracking.orgId());
        try {
            broker.updateExternalId(tracking.trackingId(), externalId);
        } catch (Exception e) {
            log.warn(
                    "Failed to update sandbox tracking externalId for row {}: {}",
                    tracking.trackingId(),
                    e.getMessage());
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }
}
