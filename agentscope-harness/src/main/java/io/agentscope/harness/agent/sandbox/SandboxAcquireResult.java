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

/**
 * Result of acquiring a {@link Sandbox} from {@link SandboxManager}.
 *
 * <p>Two ownership modes:
 *
 * <ul>
 *   <li><b>self-managed</b> ({@code selfManaged=true}): the SDK created the sandbox and is
 *       responsible for its full lifecycle — {@code stop()} + {@code shutdown()} are both called
 *       after each agent call.
 *   <li><b>user-managed</b> ({@code selfManaged=false}): the caller injected a pre-existing
 *       sandbox; the SDK only calls {@code stop()} and never calls {@code shutdown()}.
 * </ul>
 *
 * <p>When a {@link SandboxExecutionGuard} is configured, the result also carries the
 * {@link SandboxLease} that was acquired before sandbox resume/create. The harness closes it
 * after {@link SandboxManager#release} completes to cover the full call window.
 */
public final class SandboxAcquireResult {

    /** Low-cardinality source of the sandbox handle returned by {@link SandboxManager#acquire}. */
    public enum AcquisitionSource {
        EXTERNAL("external"),
        RESUME("resume"),
        CREATE("create"),
        UNKNOWN("unknown");

        private final String metricTag;

        AcquisitionSource(String metricTag) {
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }

    private final Sandbox sandbox;
    private final boolean selfManaged;
    private final SandboxLease lease;
    private final AcquisitionSource acquisitionSource;

    private SandboxAcquireResult(
            Sandbox sandbox,
            boolean selfManaged,
            SandboxLease lease,
            AcquisitionSource acquisitionSource) {
        this.sandbox = sandbox;
        this.selfManaged = selfManaged;
        this.lease = lease != null ? lease : SandboxLease.noop();
        this.acquisitionSource =
                acquisitionSource != null ? acquisitionSource : AcquisitionSource.UNKNOWN;
    }

    /** Creates a self-managed result with a guard lease (SDK owns the full lifecycle). */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox, SandboxLease lease) {
        return selfManaged(sandbox, lease, AcquisitionSource.UNKNOWN);
    }

    /** Creates a self-managed result with an explicit acquisition source. */
    public static SandboxAcquireResult selfManaged(
            Sandbox sandbox, SandboxLease lease, AcquisitionSource acquisitionSource) {
        return new SandboxAcquireResult(sandbox, true, lease, acquisitionSource);
    }

    /** Creates a self-managed result with no guard (SDK owns the full lifecycle). */
    public static SandboxAcquireResult selfManaged(Sandbox sandbox) {
        return selfManaged(sandbox, SandboxLease.noop(), AcquisitionSource.UNKNOWN);
    }

    /** Creates a user-managed result (caller owns the lifecycle; SDK only calls stop). */
    public static SandboxAcquireResult userManaged(Sandbox sandbox) {
        return new SandboxAcquireResult(
                sandbox, false, SandboxLease.noop(), AcquisitionSource.EXTERNAL);
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    /** Returns {@code true} if the SDK owns the full sandbox lifecycle. */
    public boolean isSelfManaged() {
        return selfManaged;
    }

    /**
     * Returns the {@link SandboxLease} held for this call, or a no-op lease if no guard was
     * configured. The harness closes this lease after {@link SandboxManager#release} completes.
     */
    public SandboxLease getLease() {
        return lease;
    }

    /** Returns whether the sandbox was externally supplied, resumed, freshly created, or unknown. */
    public AcquisitionSource getAcquisitionSource() {
        return acquisitionSource;
    }
}
