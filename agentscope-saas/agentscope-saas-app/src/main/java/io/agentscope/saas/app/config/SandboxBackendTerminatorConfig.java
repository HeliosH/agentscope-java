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

import io.agentscope.extensions.sandbox.cube.CubeSandboxClientOptions;
import io.agentscope.extensions.sandbox.cube.CubeSandboxTerminator;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxClientOptions;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxTerminator;
import io.agentscope.extensions.sandbox.opensandbox.OpenSandboxClientOptions;
import io.agentscope.extensions.sandbox.opensandbox.OpenSandboxTerminator;
import io.agentscope.saas.app.sandbox.SandboxBackendTerminator;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the admin backend terminator for the configured sandbox provider. */
@Configuration
public class SandboxBackendTerminatorConfig {

    @Bean
    SandboxBackendTerminator sandboxBackendTerminator(SaasProperties properties) {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        if (!sandbox.isEnabled()) {
            return SandboxBackendTerminator.unsupported();
        }
        String type = normalize(sandbox.getType());
        return switch (type) {
            case "e2b" ->
                    configured(type, new E2bSandboxTerminator(e2bOptions(sandbox))::terminate);
            case "cube" ->
                    configured(type, new CubeSandboxTerminator(cubeOptions(sandbox))::terminate);
            case "opensandbox" ->
                    configured(
                            type,
                            new OpenSandboxTerminator(openSandboxOptions(sandbox))::terminate);
            default -> SandboxBackendTerminator.unsupported();
        };
    }

    private static SandboxBackendTerminator configured(
            String configuredType, TerminateOperation operation) {
        return (sandboxType, externalId) -> {
            String rowType = normalize(sandboxType);
            if (!configuredType.equals(rowType)) {
                return SandboxBackendTerminator.TerminationResult.unsupported(
                        "configured terminator handles "
                                + configuredType
                                + ", row type is "
                                + rowType);
            }
            if (externalId == null || externalId.isBlank()) {
                return SandboxBackendTerminator.TerminationResult.noExternalId();
            }
            try {
                operation.terminate(externalId.trim());
                return SandboxBackendTerminator.TerminationResult.success();
            } catch (Exception e) {
                return SandboxBackendTerminator.TerminationResult.failed(
                        sanitizeMessage(e.getMessage()));
            }
        };
    }

    private static E2bSandboxClientOptions e2bOptions(SaasProperties.Sandbox sandbox) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setApiKey(sandbox.getE2bApiKey());
        options.setApiBaseUrl(sandbox.getE2bApiBaseUrl());
        options.setDomain(sandbox.getE2bDomain());
        options.setTemplateId(sandbox.getE2bTemplateId());
        if (sandbox.getWorkspaceRoot() != null && !sandbox.getWorkspaceRoot().isBlank()) {
            options.setWorkspaceRoot(sandbox.getWorkspaceRoot());
        }
        options.setSandboxTimeoutSeconds(sandbox.getE2bSandboxTimeoutSeconds());
        return options;
    }

    private static CubeSandboxClientOptions cubeOptions(SaasProperties.Sandbox sandbox) {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setApiKey(sandbox.getCubeApiKey());
        options.setApiUrl(sandbox.getCubeApiUrl());
        options.setDomain(sandbox.getCubeDomain());
        options.setEnvdHostPattern(sandbox.getCubeEnvdHostPattern());
        options.setTemplateId(sandbox.getCubeTemplateId());
        if (sandbox.getWorkspaceRoot() != null && !sandbox.getWorkspaceRoot().isBlank()) {
            options.setWorkspaceRoot(sandbox.getWorkspaceRoot());
        }
        options.setSandboxTimeoutSeconds(sandbox.getCubeSandboxTimeoutSeconds());
        options.setInsecureSkipTlsVerify(sandbox.isCubeInsecureSkipTlsVerify());
        return options;
    }

    private static OpenSandboxClientOptions openSandboxOptions(SaasProperties.Sandbox sandbox) {
        OpenSandboxClientOptions options = new OpenSandboxClientOptions();
        options.setApiBaseUrl(sandbox.getOpenSandboxApiBaseUrl());
        options.setApiKey(sandbox.getOpenSandboxApiKey());
        options.setExecdAccessToken(sandbox.getOpenSandboxExecdAccessToken());
        options.setImage(
                sandbox.getOpenSandboxImage() != null && !sandbox.getOpenSandboxImage().isBlank()
                        ? sandbox.getOpenSandboxImage()
                        : sandbox.getImage());
        if (sandbox.getWorkspaceRoot() != null && !sandbox.getWorkspaceRoot().isBlank()) {
            options.setWorkspaceRoot(sandbox.getWorkspaceRoot());
        }
        options.setCpuLimit(sandbox.getOpenSandboxCpuLimit());
        options.setMemoryLimit(sandbox.getOpenSandboxMemoryLimit());
        options.setSandboxTimeoutSeconds(sandbox.getOpenSandboxSandboxTimeoutSeconds());
        options.setWaitTimeoutSeconds(sandbox.getOpenSandboxWaitTimeoutSeconds());
        options.setExecdPort(sandbox.getOpenSandboxExecdPort());
        options.setDefaultExecTimeoutSeconds(sandbox.getOpenSandboxDefaultExecTimeoutSeconds());
        options.setConnectTimeoutSeconds(sandbox.getOpenSandboxConnectTimeoutSeconds());
        options.setReadTimeoutSeconds(sandbox.getOpenSandboxReadTimeoutSeconds());
        options.setMaxRetries(sandbox.getOpenSandboxMaxRetries());
        return options;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "backend termination failed";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    @FunctionalInterface
    private interface TerminateOperation {
        void terminate(String externalId) throws Exception;
    }
}
