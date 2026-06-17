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
package io.agentscope.extensions.sandbox.cube;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for Cube sandboxes (E2B-compatible private deployment). */
public class CubeFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final CubeSandboxClientOptions options = new CubeSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public CubeFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public CubeFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public CubeFilesystemSpec apiUrl(String apiUrl) {
        options.setApiUrl(apiUrl);
        return this;
    }

    public CubeFilesystemSpec domain(String domain) {
        options.setDomain(domain);
        return this;
    }

    public CubeFilesystemSpec envdHostPattern(String envdHostPattern) {
        options.setEnvdHostPattern(envdHostPattern);
        return this;
    }

    public CubeFilesystemSpec templateId(String templateId) {
        options.setTemplateId(templateId);
        return this;
    }

    public CubeFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public CubeFilesystemSpec sandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        options.setSandboxTimeoutSeconds(sandboxTimeoutSeconds);
        return this;
    }

    public CubeFilesystemSpec runUser(String runUser) {
        options.setRunUser(runUser);
        return this;
    }

    public CubeFilesystemSpec connectTimeoutSeconds(int connectTimeoutSeconds) {
        options.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return this;
    }

    public CubeFilesystemSpec readTimeoutSeconds(int readTimeoutSeconds) {
        options.setReadTimeoutSeconds(readTimeoutSeconds);
        return this;
    }

    public CubeFilesystemSpec maxRetries(int maxRetries) {
        options.setMaxRetries(maxRetries);
        return this;
    }

    public CubeFilesystemSpec insecureSkipTlsVerify(boolean insecureSkipTlsVerify) {
        options.setInsecureSkipTlsVerify(insecureSkipTlsVerify);
        return this;
    }

    public CubeFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public CubeFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
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
