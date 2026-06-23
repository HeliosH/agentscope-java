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

import io.agentscope.extensions.sandbox.cube.CubeFilesystemSpec;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.saas.storage.PgRemoteSnapshotClient;
import javax.sql.DataSource;
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

    /**
     * Creates the sandbox filesystem spec from configuration properties. Supports
     * {@code type=cube} (private deployment) and {@code type=docker} (local).
     */
    @Bean
    public SandboxFilesystemSpec sandboxFilesystemSpec(
            SaasProperties properties,
            ObjectProvider<SandboxExecutionGuard> guardProvider,
            ObjectProvider<DataSource> dataSourceProvider) {

        SaasProperties.Sandbox sb = properties.getSandbox();
        IsolationScope scope = IsolationScope.valueOf(sb.getIsolationScope());

        SandboxSnapshotSpec snapshotSpec = buildSnapshotSpec(sb, dataSourceProvider);

        SandboxFilesystemSpec spec =
                switch (sb.getType()) {
                    case "cube" -> {
                        if (sb.getCubeApiUrl() == null || sb.getCubeApiUrl().isBlank()) {
                            throw new IllegalStateException(
                                    "Cube sandbox requires saas.sandbox.cube-api-url to be set");
                        }
                        CubeFilesystemSpec cubeSpec =
                                new CubeFilesystemSpec()
                                        .apiUrl(sb.getCubeApiUrl())
                                        .workspaceRoot(
                                                sb.getWorkspaceRoot() != null
                                                                && !sb.getWorkspaceRoot().isBlank()
                                                        ? sb.getWorkspaceRoot()
                                                        : "/home/user")
                                        .sandboxTimeoutSeconds(sb.getCubeSandboxTimeoutSeconds())
                                        .insecureSkipTlsVerify(sb.isCubeInsecureSkipTlsVerify());
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
                    default ->
                            throw new IllegalStateException(
                                    "Unknown sandbox type: "
                                            + sb.getType()
                                            + ". Supported: cube, docker, e2b");
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

    private static String snapshotBackend(SaasProperties.Sandbox sb) {
        String backend = sb.getSnapshot().getBackend();
        return backend == null || backend.isBlank() ? "pg" : backend.trim().toLowerCase();
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
            SaasProperties.Sandbox sb, ObjectProvider<DataSource> dataSourceProvider) {
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
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null) {
            log.warn(
                    "Sandbox snapshot persistence requested (backend=pg) but no DataSource is "
                            + "available; workspace persistence is disabled.");
            return null;
        }
        return new RemoteSnapshotSpec(new PgRemoteSnapshotClient(ds, sb.getSnapshot().getTable()));
    }
}
