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
package io.agentscope.saas.sandbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics for SaaS sandbox runtime resource management. */
@Component
public class SandboxMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    @Autowired
    public SandboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private SandboxMetrics() {
        this.registry = null;
    }

    public static SandboxMetrics noop() {
        return new SandboxMetrics();
    }

    public void registerActive(String sandboxType) {
        incrementLifecycle(sandboxType, "registered");
    }

    public void release(String sandboxType) {
        incrementLifecycle(sandboxType, "released");
    }

    public void evict(String sandboxType) {
        incrementLifecycle(sandboxType, "evicted");
    }

    public void quotaRejected(String sandboxType) {
        incrementLifecycle(sandboxType, "quota_rejected");
    }

    public void trackingRegistrationFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "tracking_registration_failed");
    }

    public void trackingReleaseFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "tracking_release_failed");
    }

    public void trackingLeaseRefreshFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "tracking_lease_refresh_failed");
    }

    public void acquireStartFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "acquire_start_failed");
    }

    public void workspaceProjectionSucceeded(String sandboxType) {
        incrementLifecycle(sandboxType, "workspace_projection_succeeded");
    }

    public void workspaceProjectionFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "workspace_projection_failed");
    }

    public void statePersistFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "state_persist_failed");
    }

    public void sandboxStopFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "sandbox_stop_failed");
    }

    public void sandboxShutdownFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "sandbox_shutdown_failed");
    }

    public void backendReleaseFailed(String sandboxType) {
        incrementLifecycle(sandboxType, "backend_release_failed");
    }

    public void recordRun(String sandboxType, String signalType, long durationNanos) {
        if (registry == null || durationNanos < 0) {
            return;
        }
        Timer.builder("saas.sandbox.run.duration")
                .description("Agent run duration while a sandbox tracking row is active")
                .tag("type", normalize(sandboxType))
                .tag("signal", normalize(signalType))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private void incrementLifecycle(String sandboxType, String event) {
        if (registry == null) {
            return;
        }
        io.micrometer.core.instrument.Counter.builder("saas.sandbox.lifecycle.events")
                .description("Sandbox lifecycle and resource-management events")
                .tag("type", normalize(sandboxType))
                .tag("event", event)
                .register(registry)
                .increment();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
