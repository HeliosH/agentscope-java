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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;

/**
 * Options for {@link CubeSandboxClient}. CubeSandbox is a Tencent open-source sandbox service
 * (Apache 2.0, KVM microVM) that exposes an E2B-compatible REST API for private deployment.
 */
public class CubeSandboxClientOptions extends SandboxClientOptions {

    private OkHttpClient httpClient;
    private String apiKey;

    /** Cube API base URL (e.g. {@code http://cube-api.internal:8080}). */
    private String apiUrl = "http://localhost:8080";

    /** Domain used to construct envd process execution URLs. */
    private String domain = "cube.internal";

    /**
     * Pattern for constructing the envd host URL. Supports placeholders: {@code {port}},
     * {@code {sandboxId}}, {@code {domain}}. Default matches the E2B URL pattern
     * ({@code https://{port}-{sandboxId}.{domain}}). For direct-access Cube deployments
     * you may use {@code http://{sandboxId}.{domain}:{port}} instead.
     */
    private String envdHostPattern = "https://{port}-{sandboxId}.{domain}";

    /** Cube template ID (defaults to {@code "base"}). */
    private String templateId = "base";

    /** Absolute path of the workspace root inside the sandbox. */
    private String workspaceRoot = "/home/user";

    /** Sandbox idle timeout in seconds. */
    private int sandboxTimeoutSeconds = 300;

    /** User for envd process execution. */
    private String runUser = "user";

    /** HTTP connect timeout in seconds. */
    private int connectTimeoutSeconds = 30;

    /** HTTP read timeout in seconds. */
    private int readTimeoutSeconds = 120;

    /** Maximum number of retries for transient failures. */
    private int maxRetries = 3;

    /**
     * Whether to skip TLS certificate/hostname verification for envd/platform HTTP clients. This is
     * useful for private test deployments with self-signed certificates; leave disabled in
     * production.
     */
    private boolean insecureSkipTlsVerify = false;

    /** Host paths mounted by Cube before the microVM boots. */
    private List<CubeHostMount> hostMounts = List.of();

    /** Defense-in-depth allowlist; CubeMaster enforces its own allowlist as the final boundary. */
    private List<String> allowedHostMountPrefixes = List.of("/data/shared/");

    /** Fail startup when a requested host mount is absent inside the sandbox. */
    private boolean verifyHostMounts = true;

    /** Read-only shared Skills directory inside the sandbox. Blank disables the overlay. */
    private String commonSkillsMountPath;

    /** Private-first Skills directory. Blank resolves to {@code <workspaceRoot>/skills}. */
    private String commonSkillsTargetPath;

    @Override
    public String getType() {
        return "cube";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new CubeSandboxClient(this, null);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getEnvdHostPattern() {
        return envdHostPattern;
    }

    public void setEnvdHostPattern(String envdHostPattern) {
        this.envdHostPattern = envdHostPattern;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    @Override
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public String getRunUser() {
        return runUser;
    }

    public void setRunUser(String runUser) {
        this.runUser = runUser;
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

    public boolean isInsecureSkipTlsVerify() {
        return insecureSkipTlsVerify;
    }

    public void setInsecureSkipTlsVerify(boolean insecureSkipTlsVerify) {
        this.insecureSkipTlsVerify = insecureSkipTlsVerify;
    }

    public List<CubeHostMount> getHostMounts() {
        return hostMounts;
    }

    public void setHostMounts(List<CubeHostMount> hostMounts) {
        this.hostMounts = hostMounts == null ? List.of() : List.copyOf(hostMounts);
    }

    public void addHostMount(CubeHostMount hostMount) {
        ArrayList<CubeHostMount> mounts = new ArrayList<>(hostMounts);
        mounts.add(hostMount);
        hostMounts = List.copyOf(mounts);
    }

    public List<String> getAllowedHostMountPrefixes() {
        return allowedHostMountPrefixes;
    }

    public void setAllowedHostMountPrefixes(List<String> allowedHostMountPrefixes) {
        this.allowedHostMountPrefixes =
                allowedHostMountPrefixes == null
                        ? List.of()
                        : List.copyOf(allowedHostMountPrefixes);
    }

    public boolean isVerifyHostMounts() {
        return verifyHostMounts;
    }

    public void setVerifyHostMounts(boolean verifyHostMounts) {
        this.verifyHostMounts = verifyHostMounts;
    }

    public String getCommonSkillsMountPath() {
        return commonSkillsMountPath;
    }

    public void setCommonSkillsMountPath(String commonSkillsMountPath) {
        this.commonSkillsMountPath = commonSkillsMountPath;
    }

    public String getCommonSkillsTargetPath() {
        return commonSkillsTargetPath;
    }

    public void setCommonSkillsTargetPath(String commonSkillsTargetPath) {
        this.commonSkillsTargetPath = commonSkillsTargetPath;
    }

    List<CubeHostMount> resolveHostMounts(String sessionId) {
        List<CubeHostMount> resolved =
                hostMounts.stream()
                        .map(mount -> mount.resolve(sessionId, allowedHostMountPrefixes))
                        .toList();
        long distinctTargets = resolved.stream().map(CubeHostMount::mountPath).distinct().count();
        if (distinctTargets != resolved.size()) {
            throw new IllegalArgumentException("Cube host mounts must use unique mountPath values");
        }
        return resolved;
    }
}
