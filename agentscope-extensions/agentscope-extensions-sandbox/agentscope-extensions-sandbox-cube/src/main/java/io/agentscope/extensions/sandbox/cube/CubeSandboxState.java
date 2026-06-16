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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.sandbox.SandboxState;

/**
 * State for a Cube sandbox instance. Mirrors the E2B state shape since Cube exposes an
 * E2B-compatible API.
 */
public class CubeSandboxState extends SandboxState {

    @JsonProperty("sandboxId")
    private String sandboxId;

    @JsonProperty("templateId")
    private String templateId = "base";

    @JsonProperty("sandboxDomain")
    private String sandboxDomain;

    @JsonProperty("envdAccessToken")
    private String envdAccessToken;

    @JsonProperty("envdVersion")
    private String envdVersion = "0.1.5";

    @JsonProperty("workspaceRoot")
    private String workspaceRoot = "/home/user";

    /** Whether this client owns the sandbox lifecycle (and should kill it on shutdown). */
    @JsonProperty("sandboxOwned")
    private boolean sandboxOwned = true;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getSandboxDomain() {
        return sandboxDomain;
    }

    public void setSandboxDomain(String sandboxDomain) {
        this.sandboxDomain = sandboxDomain;
    }

    public String getEnvdAccessToken() {
        return envdAccessToken;
    }

    public void setEnvdAccessToken(String envdAccessToken) {
        this.envdAccessToken = envdAccessToken;
    }

    public String getEnvdVersion() {
        return envdVersion;
    }

    public void setEnvdVersion(String envdVersion) {
        this.envdVersion = envdVersion;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public boolean isSandboxOwned() {
        return sandboxOwned;
    }

    public void setSandboxOwned(boolean sandboxOwned) {
        this.sandboxOwned = sandboxOwned;
    }
}
