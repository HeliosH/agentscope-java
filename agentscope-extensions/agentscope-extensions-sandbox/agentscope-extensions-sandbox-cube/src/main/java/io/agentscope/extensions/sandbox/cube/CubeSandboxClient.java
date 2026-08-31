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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.List;
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
    public boolean supportsRuntimeContext() {
        return true;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            CubeSandboxClientOptions options) {
        return create(workspaceSpec, snapshotSpec, options, null);
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            CubeSandboxClientOptions options,
            RuntimeContext runtimeContext) {

        CubeSandboxClientOptions merged = merge(options);
        String sessionId = UUID.randomUUID().toString();

        CubeSandboxState state = new CubeSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setTemplateId(merged.getTemplateId());
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setSandboxOwned(true);
        List<CubeHostMount> hostMounts = merged.resolveHostMounts(sessionId);
        state.setHostMounts(hostMounts);
        state.setVerifyHostMounts(merged.isVerifyHostMounts());
        state.setCommonSkillsMountPath(merged.getCommonSkillsMountPath());
        state.setCommonSkillsTargetPath(merged.getCommonSkillsTargetPath());
        List<CubeVolumeMount> volumeMounts = merged.resolveVolumeMounts(runtimeContext);
        validateCommonSkillsOverlay(merged, hostMounts, volumeMounts);
        validateDistinctMountTargets(hostMounts, volumeMounts);
        state.setVolumeMounts(volumeMounts);
        boolean persistentWorkspace =
                volumeMounts.stream()
                        .anyMatch(
                                mount ->
                                        !mount.readOnly()
                                                && Path.of(mount.mountPath())
                                                        .normalize()
                                                        .equals(
                                                                Path.of(merged.getWorkspaceRoot())
                                                                        .normalize()));
        state.setPersistentWorkspace(persistentWorkspace);

        if (snapshotSpec != null && !persistentWorkspace) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        return new CubeSandbox(state, merged);
    }

    @Override
    public Sandbox resume(SandboxState sandboxState) {
        return resume(sandboxState, null);
    }

    @Override
    public Sandbox resume(SandboxState sandboxState, RuntimeContext runtimeContext) {
        if (!(sandboxState instanceof CubeSandboxState cubeState)) {
            throw new IllegalArgumentException(
                    "Expected CubeSandboxState, got " + sandboxState.getClass().getName());
        }
        CubeSandboxClientOptions merged = merge(null);
        boolean missingConfiguredWorkspaceVolume =
                merged.isWorkspaceVolumeEnabled()
                        && cubeState.getVolumeMounts().stream()
                                .noneMatch(
                                        mount ->
                                                !mount.readOnly()
                                                        && Path.of(mount.mountPath())
                                                                .normalize()
                                                                .equals(
                                                                        Path.of(
                                                                                        merged
                                                                                                .getWorkspaceRoot())
                                                                                .normalize()));
        if ((!merged.getVolumeMounts().isEmpty() && cubeState.getVolumeMounts().isEmpty())
                || missingConfiguredWorkspaceVolume) {
            List<CubeVolumeMount> mounts = merged.resolveVolumeMounts(runtimeContext);
            validateCommonSkillsOverlay(merged, cubeState.getHostMounts(), mounts);
            validateDistinctMountTargets(cubeState.getHostMounts(), mounts);
            cubeState.setVolumeMounts(mounts);
            cubeState.setPersistentWorkspace(
                    mounts.stream()
                            .anyMatch(
                                    mount ->
                                            !mount.readOnly()
                                                    && Path.of(mount.mountPath())
                                                            .normalize()
                                                            .equals(
                                                                    Path.of(
                                                                                    merged
                                                                                            .getWorkspaceRoot())
                                                                            .normalize())));
            if (cubeState.isPersistentWorkspace()) {
                cubeState.setSnapshot(null);
                // Force initialization against the newly attached Volume instead of trusting the
                // workspace-ready bit from the previous snapshot-backed sandbox.
                cubeState.setWorkspaceRootReady(false);
            }
        }
        return new CubeSandbox(cubeState, merged);
    }

    private static void validateDistinctMountTargets(
            List<CubeHostMount> hostMounts, List<CubeVolumeMount> volumeMounts) {
        long count = hostMounts.size() + volumeMounts.size();
        long distinct =
                java.util.stream.Stream.concat(
                                hostMounts.stream().map(CubeHostMount::mountPath),
                                volumeMounts.stream().map(CubeVolumeMount::mountPath))
                        .map(Path::of)
                        .map(Path::normalize)
                        .distinct()
                        .count();
        if (distinct != count) {
            throw new IllegalArgumentException(
                    "Cube host and Volume mounts must use unique mountPath values");
        }
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
                    SandboxErrorCode.WORKSPACE_START_ERROR, "Failed to delete Cube sandbox", e);
        }
    }

    /** Returns metadata for a persistent Cube Volume, or {@code null} when it does not exist. */
    public CubeVolumeInfo findPersistentVolume(String volumeId) {
        try {
            return platformClient().getVolume(volumeId);
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Failed to read Cube Volume " + volumeId,
                    e);
        }
    }

    /**
     * Permanently deletes a persistent Cube Volume.
     *
     * <p>Normal sandbox release deliberately does not call this method. It is intended for an
     * explicit tenant/user offboarding or retention workflow after canonical remote data has been
     * retained according to enterprise policy.
     */
    public void deletePersistentVolume(String volumeId) {
        try {
            platformClient().deleteVolume(volumeId);
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Failed to delete Cube Volume " + volumeId,
                    e);
        }
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
        merged.setInsecureSkipTlsVerify(
                override.isInsecureSkipTlsVerify() || defaultOptions.isInsecureSkipTlsVerify());
        merged.setHostMounts(
                !override.getHostMounts().isEmpty()
                        ? override.getHostMounts()
                        : defaultOptions.getHostMounts());
        merged.setAllowedHostMountPrefixes(
                !override.getAllowedHostMountPrefixes().equals(List.of("/data/shared/"))
                        ? override.getAllowedHostMountPrefixes()
                        : defaultOptions.getAllowedHostMountPrefixes());
        merged.setVerifyHostMounts(
                override.isVerifyHostMounts() && defaultOptions.isVerifyHostMounts());
        merged.setCommonSkillsMountPath(
                override.getCommonSkillsMountPath() != null
                        ? override.getCommonSkillsMountPath()
                        : defaultOptions.getCommonSkillsMountPath());
        merged.setCommonSkillsTargetPath(
                override.getCommonSkillsTargetPath() != null
                        ? override.getCommonSkillsTargetPath()
                        : defaultOptions.getCommonSkillsTargetPath());
        merged.setVolumeMounts(
                !override.getVolumeMounts().isEmpty()
                        ? override.getVolumeMounts()
                        : defaultOptions.getVolumeMounts());
        merged.setWorkspaceVolumeEnabled(
                override.isWorkspaceVolumeEnabled() || defaultOptions.isWorkspaceVolumeEnabled());
        merged.setWorkspaceVolumeDriver(
                override.getWorkspaceVolumeDriver() != null
                        ? override.getWorkspaceVolumeDriver()
                        : defaultOptions.getWorkspaceVolumeDriver());
        merged.setWorkspaceVolumeNamePrefix(
                !"agentscope-ws".equals(override.getWorkspaceVolumeNamePrefix())
                        ? override.getWorkspaceVolumeNamePrefix()
                        : defaultOptions.getWorkspaceVolumeNamePrefix());
        merged.setWorkspaceVolumeNamespaceFactory(
                override.getWorkspaceVolumeNamespaceFactory() != null
                        ? override.getWorkspaceVolumeNamespaceFactory()
                        : defaultOptions.getWorkspaceVolumeNamespaceFactory());
        merged.setHttpClient(
                override.getHttpClient() != null
                        ? override.getHttpClient()
                        : defaultOptions.getHttpClient());
        return merged;
    }

    private CubePlatformHttp platformClient() {
        CubeSandboxClientOptions options = copy(defaultOptions);
        return new CubePlatformHttp(CubeHttpClients.create(options), objectMapper, options);
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
        c.setInsecureSkipTlsVerify(src.isInsecureSkipTlsVerify());
        c.setHostMounts(src.getHostMounts());
        c.setAllowedHostMountPrefixes(src.getAllowedHostMountPrefixes());
        c.setVerifyHostMounts(src.isVerifyHostMounts());
        c.setCommonSkillsMountPath(src.getCommonSkillsMountPath());
        c.setCommonSkillsTargetPath(src.getCommonSkillsTargetPath());
        c.setVolumeMounts(src.getVolumeMounts());
        c.setWorkspaceVolumeEnabled(src.isWorkspaceVolumeEnabled());
        c.setWorkspaceVolumeDriver(src.getWorkspaceVolumeDriver());
        c.setWorkspaceVolumeNamePrefix(src.getWorkspaceVolumeNamePrefix());
        c.setWorkspaceVolumeNamespaceFactory(src.getWorkspaceVolumeNamespaceFactory());
        c.setHttpClient(src.getHttpClient());
        return c;
    }

    private static void validateCommonSkillsOverlay(
            CubeSandboxClientOptions options,
            List<CubeHostMount> hostMounts,
            List<CubeVolumeMount> volumeMounts) {
        String source = options.getCommonSkillsMountPath();
        if (source == null || source.isBlank()) {
            return;
        }
        Path sourcePath = Path.of(source).normalize();
        boolean readOnlyHostMount =
                hostMounts.stream()
                        .anyMatch(
                                mount ->
                                        mount.readOnly()
                                                && Path.of(mount.mountPath())
                                                        .normalize()
                                                        .equals(sourcePath));
        boolean readOnlyVolumeMount =
                volumeMounts.stream()
                        .anyMatch(
                                mount ->
                                        mount.readOnly()
                                                && Path.of(mount.mountPath())
                                                        .normalize()
                                                        .equals(sourcePath));
        if (!readOnlyHostMount && !readOnlyVolumeMount) {
            throw new IllegalArgumentException(
                    "Cube common Skills source must be backed by a read-only host or Volume mount");
        }
        String configuredTarget = options.getCommonSkillsTargetPath();
        Path workspace = Path.of(options.getWorkspaceRoot()).normalize();
        Path target =
                configuredTarget == null || configuredTarget.isBlank()
                        ? workspace.resolve("skills")
                        : Path.of(configuredTarget).normalize();
        if (!target.isAbsolute() || !target.startsWith(workspace) || target.equals(workspace)) {
            throw new IllegalArgumentException(
                    "Cube common Skills target must be a child of the workspace root");
        }
    }
}
