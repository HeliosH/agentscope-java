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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.WorkspaceProjectionSink;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative sandbox filesystem configuration.
 *
 * <p>Unlike {@code AbstractFilesystem}, this type is not a runtime filesystem implementation.
 * It only describes how to create a sandbox-backed filesystem at build time.
 */
public abstract class SandboxFilesystemSpec {

    private static final List<String> DEFAULT_WORKSPACE_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache");

    private IsolationScope isolationScope;
    private SandboxSnapshotSpec snapshotSpecOverride;
    private SandboxExecutionGuard executionGuard;
    private boolean workspaceProjectionEnabled = true;
    private List<String> workspaceProjectionRoots = DEFAULT_WORKSPACE_PROJECTION_ROOTS;

    /**
     * Optional remote projection backend (F3-S2). When set, the sandbox-backed filesystem
     * dual-writes file uploads here during a call and delegates out-of-call file IO (read/write/
     * edit/exists/ls/download) to a {@link io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem}
     * built from this store + namespace factory. This closes the "No active sandbox" gap for
     * MEMORY.md / skills / etc. between calls. {@code null} (default) disables projection.
     */
    private BaseStore remoteProjectionStore;

    private NamespaceFactory remoteProjectionNamespaceFactory;

    private WorkspaceProjectionSink workspaceProjectionSink = WorkspaceProjectionSink.noop();

    protected abstract SandboxClient<?> createClient();

    protected abstract SandboxClientOptions clientOptions();

    protected abstract SandboxSnapshotSpec snapshotSpec();

    protected abstract WorkspaceSpec workspaceSpec();

    public SandboxFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public SandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpecOverride = snapshotSpec;
        return this;
    }

    public SandboxSnapshotSpec getSnapshotSpecOverride() {
        return snapshotSpecOverride;
    }

    /**
     * Sets a {@link SandboxExecutionGuard} that serialises concurrent executions on the same
     * isolation slot.
     *
     * <p>Only relevant for {@link io.agentscope.harness.agent.IsolationScope#AGENT} and
     * {@link io.agentscope.harness.agent.IsolationScope#GLOBAL} scopes, where multiple callers
     * could otherwise race on the same persistent state. When {@code null} (default), no guard is
     * applied and the existing no-lock behaviour is preserved.
     *
     * @param executionGuard the guard to apply, or {@code null} for no guard
     * @return this spec
     */
    public SandboxFilesystemSpec executionGuard(SandboxExecutionGuard executionGuard) {
        this.executionGuard = executionGuard;
        return this;
    }

    public SandboxExecutionGuard getExecutionGuard() {
        return executionGuard;
    }

    /**
     * Configures the remote projection backend (F3-S2). When both store and namespace factory are
     * non-null, the built sandbox filesystem will dual-write uploads and delegate out-of-call file
     * IO to a {@link io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem} backed by this
     * store, so workspace files (MEMORY.md, skills, …) remain accessible between calls.
     *
     * @param store the per-file KV store backing the projection (e.g. Redis/Oss BaseStore)
     * @param namespaceFactory per-tenant namespace factory (same semantics as RemoteFilesystemSpec's)
     * @return this spec
     */
    public SandboxFilesystemSpec remoteProjection(
            BaseStore store, NamespaceFactory namespaceFactory) {
        this.remoteProjectionStore = store;
        this.remoteProjectionNamespaceFactory = namespaceFactory;
        return this;
    }

    public BaseStore getRemoteProjectionStore() {
        return remoteProjectionStore;
    }

    public NamespaceFactory getRemoteProjectionNamespaceFactory() {
        return remoteProjectionNamespaceFactory;
    }

    /** Whether a remote projection backend is configured. */
    public boolean hasRemoteProjection() {
        return remoteProjectionStore != null && remoteProjectionNamespaceFactory != null;
    }

    /**
     * Optional release-time projection sink. Use this for secondary durable catalogs/audit trails
     * that should observe files produced inside the sandbox.
     */
    public SandboxFilesystemSpec workspaceProjectionSink(WorkspaceProjectionSink sink) {
        this.workspaceProjectionSink = sink != null ? sink : WorkspaceProjectionSink.noop();
        return this;
    }

    public WorkspaceProjectionSink getWorkspaceProjectionSink() {
        return workspaceProjectionSink;
    }

    public SandboxFilesystemSpec workspaceProjectionEnabled(boolean enabled) {
        this.workspaceProjectionEnabled = enabled;
        return this;
    }

    public SandboxFilesystemSpec workspaceProjectionRoots(List<String> includeRoots) {
        this.workspaceProjectionRoots =
                includeRoots != null
                        ? List.copyOf(includeRoots)
                        : DEFAULT_WORKSPACE_PROJECTION_ROOTS;
        return this;
    }

    public final SandboxContext toSandboxContext(Path hostWorkspaceRoot) {
        SandboxClient<?> client =
                Objects.requireNonNull(createClient(), "sandbox client is required");
        WorkspaceSpec withProjection = buildWorkspaceSpecWithProjection(hostWorkspaceRoot);
        return SandboxContext.builder()
                .client(client)
                .clientOptions(clientOptions())
                .snapshotSpec(snapshotSpecOverride != null ? snapshotSpecOverride : snapshotSpec())
                .workspaceSpec(withProjection)
                .isolationScope(isolationScope)
                .build();
    }

    public final SandboxContext toSandboxContext() {
        return toSandboxContext(null);
    }

    private WorkspaceSpec buildWorkspaceSpecWithProjection(Path hostWorkspaceRoot) {
        WorkspaceSpec base = workspaceSpec();
        WorkspaceSpec effective = base != null ? base.copy() : new WorkspaceSpec();
        if (!workspaceProjectionEnabled || hostWorkspaceRoot == null) {
            return effective;
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(hostWorkspaceRoot.toAbsolutePath().normalize().toString());
        projection.setIncludeRoots(workspaceProjectionRoots);

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>(effective.getEntries());
        entries.put("__workspace_projection__", projection);
        effective.setEntries(entries);
        return effective;
    }
}
