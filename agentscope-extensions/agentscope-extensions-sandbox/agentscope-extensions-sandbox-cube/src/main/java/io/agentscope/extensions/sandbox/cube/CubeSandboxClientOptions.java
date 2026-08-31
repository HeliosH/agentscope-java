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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Deployment-defined persistent Volumes attached to every Cube sandbox. */
    private List<CubeVolumeMount> volumeMounts = List.of();

    /** Whether to provision and mount one persistent workspace Volume per isolation namespace. */
    private boolean workspaceVolumeEnabled;

    /** Optional Cube Volume plugin driver. Blank lets Cube select its default driver. */
    private String workspaceVolumeDriver;

    /** Prefix for deterministic, non-secret workspace Volume identifiers. */
    private String workspaceVolumeNamePrefix = "agentscope-ws";

    /** Resolves the tenant/user isolation tuple used to derive the workspace Volume identifier. */
    private NamespaceFactory workspaceVolumeNamespaceFactory;

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

    public List<CubeVolumeMount> getVolumeMounts() {
        return volumeMounts;
    }

    public void setVolumeMounts(List<CubeVolumeMount> volumeMounts) {
        this.volumeMounts = volumeMounts == null ? List.of() : List.copyOf(volumeMounts);
    }

    public boolean isWorkspaceVolumeEnabled() {
        return workspaceVolumeEnabled;
    }

    public void setWorkspaceVolumeEnabled(boolean workspaceVolumeEnabled) {
        this.workspaceVolumeEnabled = workspaceVolumeEnabled;
    }

    public String getWorkspaceVolumeDriver() {
        return workspaceVolumeDriver;
    }

    public void setWorkspaceVolumeDriver(String workspaceVolumeDriver) {
        this.workspaceVolumeDriver = workspaceVolumeDriver;
    }

    public String getWorkspaceVolumeNamePrefix() {
        return workspaceVolumeNamePrefix;
    }

    public void setWorkspaceVolumeNamePrefix(String workspaceVolumeNamePrefix) {
        this.workspaceVolumeNamePrefix = normalizeVolumePrefix(workspaceVolumeNamePrefix);
    }

    public NamespaceFactory getWorkspaceVolumeNamespaceFactory() {
        return workspaceVolumeNamespaceFactory;
    }

    public void setWorkspaceVolumeNamespaceFactory(NamespaceFactory namespaceFactory) {
        this.workspaceVolumeNamespaceFactory = namespaceFactory;
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

    List<CubeVolumeMount> resolveVolumeMounts(RuntimeContext runtimeContext) {
        ArrayList<CubeVolumeMount> resolved = new ArrayList<>(volumeMounts);
        if (workspaceVolumeEnabled) {
            if (workspaceVolumeNamespaceFactory == null) {
                throw new IllegalStateException(
                        "Cube workspace Volume requires an isolation namespace factory");
            }
            List<String> namespace = workspaceVolumeNamespaceFactory.getNamespace(runtimeContext);
            if (namespace == null
                    || namespace.isEmpty()
                    || namespace.stream()
                            .anyMatch(
                                    value ->
                                            value == null
                                                    || value.isBlank()
                                                    || "_anonymous".equals(value))) {
                throw new IllegalStateException(
                        "Cube workspace Volume requires an authenticated isolation namespace");
            }
            String canonical = String.join("\u0000", namespace);
            String volumeId = workspaceVolumeNamePrefix + "-" + sha256(canonical).substring(0, 40);
            resolved.add(
                    CubeVolumeMount.managed(volumeId, workspaceRoot, false, workspaceVolumeDriver));
        }
        long distinctTargets = resolved.stream().map(CubeVolumeMount::mountPath).distinct().count();
        if (distinctTargets != resolved.size()) {
            throw new IllegalArgumentException("Cube Volumes must use unique mountPath values");
        }
        return List.copyOf(resolved);
    }

    private static String normalizeVolumePrefix(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? "agentscope-ws"
                        : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid Cube workspace Volume prefix: " + value);
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
