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
package io.agentscope.saas.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.cube.CubeFilesystemSpec;
import io.agentscope.extensions.sandbox.cube.CubeHostMount;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.extensions.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.saas.dal.mybatis.tenant.RelationalBlobStorageMapper;
import io.agentscope.saas.sandbox.ActiveSandboxDeployment;
import io.agentscope.saas.sandbox.SandboxCapability;
import io.agentscope.saas.storage.PgRemoteSnapshotClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the sandbox filesystem spec and execution guard when sandbox execution is enabled. The
 * framework's {@code HarnessAgent.Builder.filesystem(SandboxFilesystemSpec)} consumes this spec to
 * create {@code SandboxManager}, {@code SessionSandboxStateStore}, {@code SandboxLifecycleMiddleware},
 * and {@code SandboxBackedFilesystem} internally — no manual wiring required.
 *
 * <p>When {@code saas.sandbox.enabled=false} (the default), this configuration class is not loaded
 * and the agent runs without sandbox support (shell tool disabled).
 */
@Configuration
@ConditionalOnProperty(prefix = "saas.sandbox", name = "enabled", havingValue = "true")
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    public ActiveSandboxDeployment activeSandboxDeployment(
            SaasProperties properties, ObjectMapper objectMapper) {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        if (sandbox.getType() == null || sandbox.getType().isBlank()) {
            throw new IllegalStateException("saas.sandbox.type must be configured");
        }
        String provider = sandbox.getType().trim().toLowerCase(Locale.ROOT);
        Set<SandboxCapability> capabilities = new LinkedHashSet<>();
        if (sandbox.getSnapshot().isEnabled()) {
            capabilities.add(SandboxCapability.SNAPSHOT);
        }
        String imageOrTemplate =
                switch (provider) {
                    case "docker" -> {
                        capabilities.add(SandboxCapability.RESOURCE_LIMITS);
                        capabilities.add(SandboxCapability.CUSTOM_IMAGE);
                        yield sandbox.getImage();
                    }
                    case "e2b" -> {
                        capabilities.add(SandboxCapability.CUSTOM_TEMPLATE);
                        yield sandbox.getE2bTemplateId();
                    }
                    case "cube" -> {
                        capabilities.add(SandboxCapability.CUSTOM_TEMPLATE);
                        yield sandbox.getCubeTemplateId();
                    }
                    case "opensandbox" -> {
                        capabilities.add(SandboxCapability.RESOURCE_LIMITS);
                        capabilities.add(SandboxCapability.CUSTOM_IMAGE);
                        yield openSandboxImage(sandbox);
                    }
                    default -> throw new IllegalStateException("Unknown sandbox type: " + provider);
                };
        Set<SandboxCapability> requiredCapabilities =
                sandbox.getRequiredCapabilities().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(SandboxCapability::parse)
                        .collect(Collectors.toUnmodifiableSet());
        try {
            ActiveSandboxDeployment deployment =
                    new ActiveSandboxDeployment(
                            provider,
                            imageOrTemplate,
                            capabilities,
                            objectMapper.writeValueAsString(Map.of("capabilities", capabilities)));
            deployment.require(requiredCapabilities);
            return deployment;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize sandbox capabilities", e);
        }
    }

    /**
     * Creates the sandbox filesystem spec from configuration properties. Supports
     * {@code type=cube} (private deployment) and {@code type=docker} (local).
     */
    @Bean
    public SandboxFilesystemSpec sandboxFilesystemSpec(
            SaasProperties properties,
            ObjectProvider<SandboxExecutionGuard> guardProvider,
            ObjectProvider<RelationalBlobStorageMapper> blobMapperProvider,
            ObjectMapper objectMapper) {

        SaasProperties.Sandbox sb = properties.getSandbox();
        IsolationScope scope = IsolationScope.valueOf(sb.getIsolationScope());

        SandboxSnapshotSpec snapshotSpec = buildSnapshotSpec(sb, blobMapperProvider);

        SandboxFilesystemSpec spec =
                switch (sb.getType()) {
                    case "cube" -> {
                        if (sb.getCubeApiUrl() == null || sb.getCubeApiUrl().isBlank()) {
                            throw new IllegalStateException(
                                    "Cube sandbox requires saas.sandbox.cube-api-url to be set");
                        }
                        String workspaceRoot =
                                sb.getWorkspaceRoot() != null && !sb.getWorkspaceRoot().isBlank()
                                        ? sb.getWorkspaceRoot()
                                        : "/home/user";
                        List<CubeHostMount> hostMounts = cubeHostMounts(sb, objectMapper);
                        CubeFilesystemSpec cubeSpec =
                                new CubeFilesystemSpec()
                                        .apiUrl(sb.getCubeApiUrl())
                                        .workspaceRoot(workspaceRoot)
                                        .sandboxTimeoutSeconds(sb.getCubeSandboxTimeoutSeconds())
                                        .insecureSkipTlsVerify(sb.isCubeInsecureSkipTlsVerify())
                                        .allowedHostMountPrefixes(
                                                sb.getCubeAllowedHostMountPrefixes())
                                        .verifyHostMounts(sb.isCubeVerifyHostMounts())
                                        .hostMounts(hostMounts);
                        if (sb.getCubeApiKey() != null) {
                            cubeSpec.apiKey(sb.getCubeApiKey());
                        }
                        if (sb.getCubeTemplateId() != null) {
                            cubeSpec.templateId(sb.getCubeTemplateId());
                        }
                        if (sb.getCubeDomain() != null) {
                            cubeSpec.domain(sb.getCubeDomain());
                        }
                        if (sb.getCubeEnvdHostPattern() != null) {
                            cubeSpec.envdHostPattern(sb.getCubeEnvdHostPattern());
                        }
                        if (sb.getCubeCommonSkillsHostPath() != null
                                && !sb.getCubeCommonSkillsHostPath().isBlank()) {
                            validateCommonSkillsTarget(sb, workspaceRoot);
                            cubeSpec.commonSkillsOverlay(
                                    sb.getCubeCommonSkillsMountPath(),
                                    sb.getCubeCommonSkillsTargetPath());
                        }
                        log.info(
                                "Cube host mounts configured: count={}, commonSkills={}",
                                hostMounts.size(),
                                sb.getCubeCommonSkillsHostPath() != null
                                        && !sb.getCubeCommonSkillsHostPath().isBlank());
                        cubeSpec.isolationScope(scope);
                        if (snapshotSpec != null) {
                            cubeSpec.snapshotSpec(snapshotSpec);
                        }
                        yield cubeSpec;
                    }
                    case "docker" -> {
                        DockerFilesystemSpec dockerSpec =
                                new DockerFilesystemSpec()
                                        .image(sb.getImage())
                                        .workspaceRoot(
                                                sb.getWorkspaceRoot() != null
                                                                && !sb.getWorkspaceRoot().isBlank()
                                                        ? sb.getWorkspaceRoot()
                                                        : "/workspace");
                        if (sb.getMemoryLimitBytes() != null) {
                            dockerSpec.memorySizeBytes(sb.getMemoryLimitBytes());
                        }
                        if (sb.getCpuCount() != null) {
                            dockerSpec.cpuCount(sb.getCpuCount());
                        }
                        dockerSpec.isolationScope(scope);
                        if (snapshotSpec != null) {
                            dockerSpec.snapshotSpec(snapshotSpec);
                        }
                        yield dockerSpec;
                    }
                    case "e2b" -> {
                        if (sb.getE2bApiKey() == null || sb.getE2bApiKey().isBlank()) {
                            throw new IllegalStateException(
                                    "E2B sandbox requires saas.sandbox.e2b-api-key to be set");
                        }
                        E2bFilesystemSpec e2bSpec =
                                new E2bFilesystemSpec()
                                        .apiKey(sb.getE2bApiKey())
                                        .sandboxTimeoutSeconds(sb.getE2bSandboxTimeoutSeconds());
                        if (sb.getWorkspaceRoot() != null && !sb.getWorkspaceRoot().isBlank()) {
                            e2bSpec.workspaceRoot(sb.getWorkspaceRoot());
                        }
                        if (sb.getE2bApiBaseUrl() != null && !sb.getE2bApiBaseUrl().isBlank()) {
                            e2bSpec.apiBaseUrl(sb.getE2bApiBaseUrl());
                        }
                        if (sb.getE2bTemplateId() != null && !sb.getE2bTemplateId().isBlank()) {
                            e2bSpec.templateId(sb.getE2bTemplateId());
                        }
                        if (sb.getE2bDomain() != null && !sb.getE2bDomain().isBlank()) {
                            e2bSpec.domain(sb.getE2bDomain());
                        }
                        e2bSpec.isolationScope(scope);
                        if (snapshotSpec != null) {
                            e2bSpec.snapshotSpec(snapshotSpec);
                        }
                        yield e2bSpec;
                    }
                    case "opensandbox" -> {
                        if (sb.getOpenSandboxApiBaseUrl() == null
                                || sb.getOpenSandboxApiBaseUrl().isBlank()) {
                            throw new IllegalStateException(
                                    "OpenSandbox requires "
                                            + "saas.sandbox.open-sandbox-api-base-url to be set");
                        }
                        OpenSandboxFilesystemSpec openSandboxSpec =
                                new OpenSandboxFilesystemSpec()
                                        .apiBaseUrl(sb.getOpenSandboxApiBaseUrl())
                                        .image(openSandboxImage(sb))
                                        .workspaceRoot(
                                                sb.getWorkspaceRoot() != null
                                                                && !sb.getWorkspaceRoot().isBlank()
                                                        ? sb.getWorkspaceRoot()
                                                        : "/workspace")
                                        .cpuLimit(sb.getOpenSandboxCpuLimit())
                                        .memoryLimit(sb.getOpenSandboxMemoryLimit())
                                        .sandboxTimeoutSeconds(
                                                sb.getOpenSandboxSandboxTimeoutSeconds())
                                        .waitTimeoutSeconds(sb.getOpenSandboxWaitTimeoutSeconds())
                                        .execdPort(sb.getOpenSandboxExecdPort())
                                        .defaultExecTimeoutSeconds(
                                                sb.getOpenSandboxDefaultExecTimeoutSeconds())
                                        .connectTimeoutSeconds(
                                                sb.getOpenSandboxConnectTimeoutSeconds())
                                        .readTimeoutSeconds(sb.getOpenSandboxReadTimeoutSeconds())
                                        .maxRetries(sb.getOpenSandboxMaxRetries());
                        if (sb.getOpenSandboxApiKey() != null
                                && !sb.getOpenSandboxApiKey().isBlank()) {
                            openSandboxSpec.apiKey(sb.getOpenSandboxApiKey());
                        }
                        if (sb.getOpenSandboxExecdAccessToken() != null
                                && !sb.getOpenSandboxExecdAccessToken().isBlank()) {
                            openSandboxSpec.execdAccessToken(sb.getOpenSandboxExecdAccessToken());
                        }
                        openSandboxSpec.isolationScope(scope);
                        if (snapshotSpec != null) {
                            openSandboxSpec.snapshotSpec(snapshotSpec);
                        }
                        yield openSandboxSpec;
                    }
                    default ->
                            throw new IllegalStateException(
                                    "Unknown sandbox type: "
                                            + sb.getType()
                                            + ". Supported: cube, docker, e2b, opensandbox");
                };

        SandboxExecutionGuard guard = guardProvider.getIfAvailable();
        if (guard != null) {
            spec.executionGuard(guard);
        }
        log.info(
                "Sandbox filesystem spec: type={}, scope={}, guard={}, snapshot={}",
                sb.getType(),
                scope,
                guard != null ? "redis" : "none",
                snapshotSpec != null ? snapshotBackend(sb) : "none");

        return spec;
    }

    static List<CubeHostMount> cubeHostMounts(
            SaasProperties.Sandbox sandbox, ObjectMapper objectMapper) {
        List<CubeHostMount> mounts = new ArrayList<>();
        String json = sandbox.getCubeHostMountsJson();
        if (json != null && !json.isBlank() && !"[]".equals(json.trim())) {
            try {
                mounts.addAll(
                        objectMapper.readValue(json, new TypeReference<List<CubeHostMount>>() {}));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "saas.sandbox.cube-host-mounts-json must be a valid mount array", e);
            }
        }
        String commonSkillsHostPath = sandbox.getCubeCommonSkillsHostPath();
        if (commonSkillsHostPath != null && !commonSkillsHostPath.isBlank()) {
            String mountPath = sandbox.getCubeCommonSkillsMountPath();
            if (mountPath == null || mountPath.isBlank()) {
                throw new IllegalStateException("Cube common Skills mount path must not be blank");
            }
            mounts.add(new CubeHostMount(commonSkillsHostPath, mountPath, true));
        }
        mounts.forEach(
                mount ->
                        mount.validateAllowedHostPrefixes(
                                sandbox.getCubeAllowedHostMountPrefixes()));
        long distinctTargets = mounts.stream().map(CubeHostMount::mountPath).distinct().count();
        if (distinctTargets != mounts.size()) {
            throw new IllegalStateException("Cube host mounts must use unique mountPath values");
        }
        return List.copyOf(mounts);
    }

    static void validateCommonSkillsTarget(SaasProperties.Sandbox sandbox, String workspaceRoot) {
        String configuredTarget = sandbox.getCubeCommonSkillsTargetPath();
        Path workspace = Path.of(workspaceRoot).normalize();
        Path target =
                configuredTarget == null || configuredTarget.isBlank()
                        ? workspace.resolve("skills")
                        : Path.of(configuredTarget).normalize();
        if (!workspace.isAbsolute()
                || !target.isAbsolute()
                || !target.startsWith(workspace)
                || target.equals(workspace)) {
            throw new IllegalStateException(
                    "Cube common Skills target must be a child of the workspace root");
        }
    }

    private static String snapshotBackend(SaasProperties.Sandbox sb) {
        String backend = sb.getSnapshot().getBackend();
        return backend == null || backend.isBlank() ? "pg" : backend.trim().toLowerCase();
    }

    private static String openSandboxImage(SaasProperties.Sandbox sb) {
        String image = sb.getOpenSandboxImage();
        if (image == null || image.isBlank()) {
            image = sb.getImage();
        }
        return image == null || image.isBlank() ? "ubuntu:latest" : image;
    }

    /**
     * Builds the durable workspace snapshot spec. Without this, sandbox workspaces (including the
     * agent's MEMORY.md) are discarded when the sandbox is stopped or evicted by TTL — the user's
     * memory would not survive.
     *
     * <p>Backend is selected by {@code saas.sandbox.snapshot.backend}:
     *
     * <ul>
     *   <li>{@code pg} (default) — Postgres {@code BYTEA}; zero-infra dev/H2 fallback.
     *   <li>{@code minio} — S3-compatible object storage; production. Requires a running MinIO/S3
     *       at {@code saas.sandbox.minio.endpoint}. Object keys are {@code <keyPrefix>/<snapshotId>.tar.gz}.
     * </ul>
     */
    private SandboxSnapshotSpec buildSnapshotSpec(
            SaasProperties.Sandbox sb,
            ObjectProvider<RelationalBlobStorageMapper> blobMapperProvider) {
        if (!sb.getSnapshot().isEnabled()) {
            log.warn(
                    "Sandbox snapshot persistence is DISABLED — workspace files (incl. MEMORY.md) "
                            + "will be lost when a sandbox is evicted.");
            return null;
        }
        String backend =
                sb.getSnapshot().getBackend() == null ? "pg" : sb.getSnapshot().getBackend();
        if ("minio".equalsIgnoreCase(backend)) {
            SaasProperties.Sandbox.Minio m = sb.getMinio();
            return new RemoteSnapshotSpec(
                    io.agentscope.saas.storage.MinioSnapshotClientFactory.create(
                            m.getEndpoint(),
                            m.getAccessKey(),
                            m.getSecretKey(),
                            m.getRegion(),
                            m.getBucket(),
                            m.getKeyPrefix()));
        }
        RelationalBlobStorageMapper mapper = blobMapperProvider.getIfAvailable();
        if (mapper == null) {
            log.warn(
                    "Sandbox snapshot persistence requested (backend=pg) but no tenant MyBatis "
                            + "mapper is available; workspace persistence is disabled.");
            return null;
        }
        return new RemoteSnapshotSpec(
                new PgRemoteSnapshotClient(mapper, sb.getSnapshot().getTable()));
    }
}
