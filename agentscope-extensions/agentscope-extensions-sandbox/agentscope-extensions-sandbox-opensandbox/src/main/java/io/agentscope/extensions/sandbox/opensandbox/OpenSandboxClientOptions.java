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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import okhttp3.OkHttpClient;

/** Options for {@link OpenSandboxClient}. */
public class OpenSandboxClientOptions extends SandboxClientOptions {

    public static final String DEFAULT_API_BASE_URL = "http://localhost:8080";
    public static final String DEFAULT_WORKSPACE_ROOT = "/workspace";
    public static final int DEFAULT_EXECD_PORT = 44772;

    private OkHttpClient httpClient;
    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private String apiKey;
    private String execdAccessToken;
    private String image = "ubuntu:latest";
    private String workspaceRoot = DEFAULT_WORKSPACE_ROOT;
    private String cpuLimit = "1";
    private String memoryLimit = "1Gi";
    private int sandboxTimeoutSeconds = 300;
    private int waitTimeoutSeconds = 120;
    private int execdPort = DEFAULT_EXECD_PORT;
    private int defaultExecTimeoutSeconds = 120;
    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 300;
    private int maxRetries = 3;

    @Override
    public String getType() {
        return "opensandbox";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new OpenSandboxClient(this, null);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getExecdAccessToken() {
        return execdAccessToken;
    }

    public void setExecdAccessToken(String execdAccessToken) {
        this.execdAccessToken = execdAccessToken;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public void setCpuLimit(String cpuLimit) {
        this.cpuLimit = cpuLimit;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public void setMemoryLimit(String memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public int getWaitTimeoutSeconds() {
        return waitTimeoutSeconds;
    }

    public void setWaitTimeoutSeconds(int waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

    public int getExecdPort() {
        return execdPort;
    }

    public void setExecdPort(int execdPort) {
        this.execdPort = execdPort;
    }

    public int getDefaultExecTimeoutSeconds() {
        return defaultExecTimeoutSeconds;
    }

    public void setDefaultExecTimeoutSeconds(int defaultExecTimeoutSeconds) {
        this.defaultExecTimeoutSeconds = defaultExecTimeoutSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
