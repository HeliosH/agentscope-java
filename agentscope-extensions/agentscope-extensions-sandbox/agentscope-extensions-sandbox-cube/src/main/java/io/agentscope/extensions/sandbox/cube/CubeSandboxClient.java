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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.UUID;

/**
 * CubeSandbox client implementing the framework's {@link SandboxClient} interface. CubeSandbox
 * (Tencent open-source, Apache 2.0, KVM microVM) exposes an E2B-compatible REST API, making this
 * client structurally similar to {@code E2bSandboxClient} but with private-deployment defaults.
 */
public class CubeSandboxClient implements SandboxClient<CubeSandboxClientOptions> {

    private final CubeSandboxClientOptions defaultOptions;
    private final ObjectMapper objectMapper;

    public CubeSandboxClient() {
        this(new CubeSandboxClientOptions(), null);
    }

    public CubeSandboxClient(CubeSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new CubeSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(
                                        new io.agentscope.harness.agent.sandbox.json
                                                .HarnessSandboxJacksonModule())
                                .registerModule(new CubeHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            CubeSandboxClientOptions options) {

        CubeSandboxClientOptions merged = merge(options);
        String sessionId = UUID.randomUUID().toString();

        CubeSandboxState state = new CubeSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setTemplateId(merged.getTemplateId());
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setSandboxOwned(true);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        return new CubeSandbox(state, merged);
    }

    @Override
    public Sandbox resume(SandboxState sandboxState) {
        if (!(sandboxState instanceof CubeSandboxState cubeState)) {
            throw new IllegalArgumentException(
                    "Expected CubeSandboxState, got " + sandboxState.getClass().getName());
        }
        return new CubeSandbox(cubeState, merge(null));
    }

    @Override
    public void delete(Sandbox sandbox) {
        // No-op; Cube server handles cleanup via TTL
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CubeSandboxState", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize CubeSandboxState", e);
        }
    }

    private CubeSandboxClientOptions merge(CubeSandboxClientOptions override) {
        if (override == null) {
            return copy(defaultOptions);
        }
        CubeSandboxClientOptions merged = new CubeSandboxClientOptions();
        merged.setApiKey(
                override.getApiKey() != null ? override.getApiKey() : defaultOptions.getApiKey());
        merged.setApiUrl(
                override.getApiUrl() != null
                                && !override.getApiUrl().equals("http://localhost:8080")
                        ? override.getApiUrl()
                        : defaultOptions.getApiUrl());
        merged.setDomain(
                override.getDomain() != null && !override.getDomain().equals("cube.internal")
                        ? override.getDomain()
                        : defaultOptions.getDomain());
        merged.setEnvdHostPattern(
                override.getEnvdHostPattern() != null
                                && !override.getEnvdHostPattern()
                                        .equals("https://{port}-{sandboxId}.{domain}")
                        ? override.getEnvdHostPattern()
                        : defaultOptions.getEnvdHostPattern());
        merged.setTemplateId(
                override.getTemplateId() != null && !override.getTemplateId().equals("base")
                        ? override.getTemplateId()
                        : defaultOptions.getTemplateId());
        merged.setWorkspaceRoot(
                override.getWorkspaceRoot() != null
                                && !override.getWorkspaceRoot().equals("/home/user")
                        ? override.getWorkspaceRoot()
                        : defaultOptions.getWorkspaceRoot());
        merged.setSandboxTimeoutSeconds(
                override.getSandboxTimeoutSeconds() != 300
                        ? override.getSandboxTimeoutSeconds()
                        : defaultOptions.getSandboxTimeoutSeconds());
        merged.setRunUser(
                override.getRunUser() != null && !override.getRunUser().equals("user")
                        ? override.getRunUser()
                        : defaultOptions.getRunUser());
        merged.setConnectTimeoutSeconds(
                override.getConnectTimeoutSeconds() != 30
                        ? override.getConnectTimeoutSeconds()
                        : defaultOptions.getConnectTimeoutSeconds());
        merged.setReadTimeoutSeconds(
                override.getReadTimeoutSeconds() != 120
                        ? override.getReadTimeoutSeconds()
                        : defaultOptions.getReadTimeoutSeconds());
        merged.setMaxRetries(
                override.getMaxRetries() != 3
                        ? override.getMaxRetries()
                        : defaultOptions.getMaxRetries());
        merged.setHttpClient(
                override.getHttpClient() != null
                        ? override.getHttpClient()
                        : defaultOptions.getHttpClient());
        return merged;
    }

    private CubeSandboxClientOptions copy(CubeSandboxClientOptions src) {
        CubeSandboxClientOptions c = new CubeSandboxClientOptions();
        c.setApiKey(src.getApiKey());
        c.setApiUrl(src.getApiUrl());
        c.setDomain(src.getDomain());
        c.setEnvdHostPattern(src.getEnvdHostPattern());
        c.setTemplateId(src.getTemplateId());
        c.setWorkspaceRoot(src.getWorkspaceRoot());
        c.setSandboxTimeoutSeconds(src.getSandboxTimeoutSeconds());
        c.setRunUser(src.getRunUser());
        c.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
        c.setReadTimeoutSeconds(src.getReadTimeoutSeconds());
        c.setMaxRetries(src.getMaxRetries());
        c.setHttpClient(src.getHttpClient());
        return c;
    }
}
