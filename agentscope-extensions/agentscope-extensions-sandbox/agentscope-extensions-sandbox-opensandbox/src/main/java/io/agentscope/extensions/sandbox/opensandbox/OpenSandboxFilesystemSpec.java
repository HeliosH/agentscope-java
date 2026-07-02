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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for OpenSandbox lifecycle + execd sandboxes. */
public class OpenSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final OpenSandboxClientOptions options = new OpenSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public OpenSandboxFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public OpenSandboxFilesystemSpec apiBaseUrl(String apiBaseUrl) {
        options.setApiBaseUrl(apiBaseUrl);
        return this;
    }

    public OpenSandboxFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public OpenSandboxFilesystemSpec execdAccessToken(String execdAccessToken) {
        options.setExecdAccessToken(execdAccessToken);
        return this;
    }

    public OpenSandboxFilesystemSpec image(String image) {
        options.setImage(image);
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public OpenSandboxFilesystemSpec cpuLimit(String cpuLimit) {
        options.setCpuLimit(cpuLimit);
        return this;
    }

    public OpenSandboxFilesystemSpec memoryLimit(String memoryLimit) {
        options.setMemoryLimit(memoryLimit);
        return this;
    }

    public OpenSandboxFilesystemSpec sandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        options.setSandboxTimeoutSeconds(sandboxTimeoutSeconds);
        return this;
    }

    public OpenSandboxFilesystemSpec waitTimeoutSeconds(int waitTimeoutSeconds) {
        options.setWaitTimeoutSeconds(waitTimeoutSeconds);
        return this;
    }

    public OpenSandboxFilesystemSpec execdPort(int execdPort) {
        options.setExecdPort(execdPort);
        return this;
    }

    public OpenSandboxFilesystemSpec defaultExecTimeoutSeconds(int defaultExecTimeoutSeconds) {
        options.setDefaultExecTimeoutSeconds(defaultExecTimeoutSeconds);
        return this;
    }

    public OpenSandboxFilesystemSpec connectTimeoutSeconds(int connectTimeoutSeconds) {
        options.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return this;
    }

    public OpenSandboxFilesystemSpec readTimeoutSeconds(int readTimeoutSeconds) {
        options.setReadTimeoutSeconds(readTimeoutSeconds);
        return this;
    }

    public OpenSandboxFilesystemSpec maxRetries(int maxRetries) {
        options.setMaxRetries(maxRetries);
        return this;
    }

    public OpenSandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec;
        return this;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client != null ? client : options.createClient();
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return options;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpec;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return defaultWorkspaceSpec;
    }
}
