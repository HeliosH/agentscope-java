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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link SandboxClient} for OpenSandbox. */
public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxClient.class);

    private final ObjectMapper objectMapper;
    private final OpenSandboxClientOptions defaultOptions;

    public OpenSandboxClient() {
        this(new OpenSandboxClientOptions(), null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new OpenSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new OpenSandboxHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            OpenSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();
        OpenSandboxClientOptions merged = merge(options);

        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(merged.getImage());
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug("[sandbox-opensandbox] Creating sandbox sessionId={}", sessionId);
        return new OpenSandbox(state, merged);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof OpenSandboxState openSandboxState)) {
            throw new IllegalArgumentException(
                    "Expected OpenSandboxState but got: " + state.getClass().getName());
        }
        return new OpenSandbox(openSandboxState, merge(null));
    }

    @Override
    public void delete(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.shutdown();
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_STOP_ERROR,
                    "Failed to delete OpenSandbox sandbox",
                    e);
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize OpenSandbox sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize OpenSandbox sandbox state", e);
        }
    }

    private OpenSandboxClientOptions merge(OpenSandboxClientOptions call) {
        OpenSandboxClientOptions o = copy(defaultOptions);
        if (call == null) {
            return o;
        }
        if (call.getHttpClient() != null) {
            o.setHttpClient(call.getHttpClient());
        }
        if (call.getApiBaseUrl() != null) {
            o.setApiBaseUrl(call.getApiBaseUrl());
        }
        if (call.getApiKey() != null) {
            o.setApiKey(call.getApiKey());
        }
        if (call.getExecdAccessToken() != null) {
            o.setExecdAccessToken(call.getExecdAccessToken());
        }
        if (call.getImage() != null) {
            o.setImage(call.getImage());
        }
        if (call.getWorkspaceRoot() != null) {
            o.setWorkspaceRoot(call.getWorkspaceRoot());
        }
        if (call.getCpuLimit() != null) {
            o.setCpuLimit(call.getCpuLimit());
        }
        if (call.getMemoryLimit() != null) {
            o.setMemoryLimit(call.getMemoryLimit());
        }
        if (call.getSandboxTimeoutSeconds() > 0) {
            o.setSandboxTimeoutSeconds(call.getSandboxTimeoutSeconds());
        }
        if (call.getWaitTimeoutSeconds() > 0) {
            o.setWaitTimeoutSeconds(call.getWaitTimeoutSeconds());
        }
        if (call.getExecdPort() > 0) {
            o.setExecdPort(call.getExecdPort());
        }
        if (call.getDefaultExecTimeoutSeconds() > 0) {
            o.setDefaultExecTimeoutSeconds(call.getDefaultExecTimeoutSeconds());
        }
        o.setConnectTimeoutSeconds(call.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(call.getReadTimeoutSeconds());
        o.setMaxRetries(call.getMaxRetries());
        return o;
    }

    private static OpenSandboxClientOptions copy(OpenSandboxClientOptions src) {
        OpenSandboxClientOptions o = new OpenSandboxClientOptions();
        o.setHttpClient(src.getHttpClient());
        o.setApiBaseUrl(src.getApiBaseUrl());
        o.setApiKey(src.getApiKey());
        o.setExecdAccessToken(src.getExecdAccessToken());
        o.setImage(src.getImage());
        o.setWorkspaceRoot(src.getWorkspaceRoot());
        o.setCpuLimit(src.getCpuLimit());
        o.setMemoryLimit(src.getMemoryLimit());
        o.setSandboxTimeoutSeconds(src.getSandboxTimeoutSeconds());
        o.setWaitTimeoutSeconds(src.getWaitTimeoutSeconds());
        o.setExecdPort(src.getExecdPort());
        o.setDefaultExecTimeoutSeconds(src.getDefaultExecTimeoutSeconds());
        o.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(src.getReadTimeoutSeconds());
        o.setMaxRetries(src.getMaxRetries());
        return o;
    }
}
