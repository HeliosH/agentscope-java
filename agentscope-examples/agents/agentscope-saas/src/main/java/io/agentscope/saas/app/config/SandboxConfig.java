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

import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
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
     * {@code type=e2b} (cloud) and {@code type=docker} (local).
     */
    @Bean
    public SandboxFilesystemSpec sandboxFilesystemSpec(
            SaasProperties properties, ObjectProvider<SandboxExecutionGuard> guardProvider) {

        SaasProperties.Sandbox sb = properties.getSandbox();
        IsolationScope scope = IsolationScope.valueOf(sb.getIsolationScope());

        SandboxFilesystemSpec spec =
                switch (sb.getType()) {
                    case "e2b" -> {
                        if (sb.getE2bApiKey() == null || sb.getE2bApiKey().isBlank()) {
                            throw new IllegalStateException(
                                    "E2B sandbox requires saas.sandbox.e2b-api-key to be set");
                        }
                        E2bFilesystemSpec e2bSpec =
                                new E2bFilesystemSpec()
                                        .apiKey(sb.getE2bApiKey())
                                        .workspaceRoot(
                                                sb.getWorkspaceRoot() != null
                                                        ? sb.getWorkspaceRoot()
                                                        : "/home/user")
                                        .sandboxTimeoutSeconds(sb.getE2bSandboxTimeoutSeconds());
                        if (sb.getE2bApiBaseUrl() != null) {
                            e2bSpec.apiBaseUrl(sb.getE2bApiBaseUrl());
                        }
                        if (sb.getE2bTemplateId() != null) {
                            e2bSpec.templateId(sb.getE2bTemplateId());
                        }
                        if (sb.getE2bDomain() != null) {
                            e2bSpec.domain(sb.getE2bDomain());
                        }
                        e2bSpec.isolationScope(scope);
                        yield e2bSpec;
                    }
                    case "docker" -> {
                        DockerFilesystemSpec dockerSpec =
                                new DockerFilesystemSpec()
                                        .image(sb.getImage())
                                        .workspaceRoot(
                                                sb.getWorkspaceRoot() != null
                                                        ? sb.getWorkspaceRoot()
                                                        : "/workspace");
                        if (sb.getMemoryLimitBytes() != null) {
                            dockerSpec.memorySizeBytes(sb.getMemoryLimitBytes());
                        }
                        if (sb.getCpuCount() != null) {
                            dockerSpec.cpuCount(sb.getCpuCount());
                        }
                        // isolationScope is defined on the base class; set after construction
                        dockerSpec.isolationScope(scope);
                        yield dockerSpec;
                    }
                    default ->
                            throw new IllegalStateException(
                                    "Unknown sandbox type: "
                                            + sb.getType()
                                            + ". Supported: e2b, docker");
                };

        SandboxExecutionGuard guard = guardProvider.getIfAvailable();
        if (guard != null) {
            spec.executionGuard(guard);
            log.info(
                    "Sandbox filesystem spec: type={}, scope={}, guard=redis", sb.getType(), scope);
        } else {
            log.info("Sandbox filesystem spec: type={}, scope={}, guard=none", sb.getType(), scope);
        }

        return spec;
    }
}
