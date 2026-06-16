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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** Top-level configuration for the SaaS platform runtime (model, redis, sandbox, rate limit). */
@ConfigurationProperties(prefix = "saas")
public class SaasProperties {

    @NestedConfigurationProperty private final Model model = new Model();
    @NestedConfigurationProperty private final Redis redis = new Redis();
    @NestedConfigurationProperty private final Sandbox sandbox = new Sandbox();
    @NestedConfigurationProperty private final RateLimit rateLimit = new RateLimit();
    @NestedConfigurationProperty private final Agent agent = new Agent();

    public Model getModel() {
        return model;
    }

    public Redis getRedis() {
        return redis;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Agent getAgent() {
        return agent;
    }

    /** Model gateway / provider selection. */
    public static class Model {
        /** One of: stub, gateway, dashscope. */
        private String type = "stub";

        /** OpenAI-compatible base URL of the internal model gateway (for type=gateway). */
        private String baseUrl;

        /** API key for the gateway / provider. */
        private String apiKey;

        /** Model name to request (e.g. qwen-max, gpt-4o). */
        private String name = "qwen-max";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Redis (Valkey) connection. When disabled, in-memory state is used (local profile). */
    public static class Redis {
        private boolean enabled = false;
        private String uri = "redis://localhost:6379";
        private String keyPrefix = "agentscope:saas:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    /** Sandbox execution configuration. When disabled, the shell tool is removed and no
     * sandbox filesystem is configured — the agent runs without command execution capability. */
    public static class Sandbox {
        /** Whether sandbox-backed command execution is enabled. */
        private boolean enabled = false;

        /** Backend type: {@code "cube"} (private deployment), {@code "docker"} (local). */
        private String type = "cube";

        /** Docker image for sandbox containers (type=docker only). */
        private String image = "ubuntu:22.04";

        /** Workspace root path inside the sandbox. Defaults differ by type (/workspace for docker,
         * /home/user for cube). */
        private String workspaceRoot;

        /** Isolation scope: {@code USER} (default, one sandbox per user), {@code SESSION},
         * {@code AGENT}, or {@code GLOBAL}. */
        private String isolationScope = "USER";

        /** Memory limit per sandbox in bytes ({@code null} = unlimited, type=docker only). */
        private Long memoryLimitBytes;

        /** CPU count per sandbox ({@code null} = unlimited, type=docker only). */
        private Long cpuCount;

        /** Idle TTL in seconds before a sandbox is eligible for eviction. */
        private int idleTtlSeconds = 600;

        // --- Cube-specific fields ---

        /** Cube API base URL (e.g. {@code http://cube-api.internal:8080}). Required when type=cube. */
        private String cubeApiUrl;

        /** Cube API key for authentication. */
        private String cubeApiKey;

        /** Cube template ID (defaults to {@code "base"}). */
        private String cubeTemplateId;

        /** Cube sandbox domain used for envd process execution URLs. */
        private String cubeDomain;

        /**
         * Cube envd host URL pattern. Supports placeholders: {@code {port}}, {@code {sandboxId}},
         * {@code {domain}}. Default: {@code https://{port}-{sandboxId}.{domain}}.
         */
        private String cubeEnvdHostPattern;

        /** Cube sandbox idle timeout in seconds (defaults to 300). */
        private int cubeSandboxTimeoutSeconds = 300;

        @NestedConfigurationProperty private final Snapshot snapshot = new Snapshot();

        public Snapshot getSnapshot() {
            return snapshot;
        }

        /**
         * Durable workspace snapshot persistence. When enabled, the agent's sandbox workspace
         * (files, MEMORY.md, etc.) is archived to durable storage on sandbox stop and restored on
         * the next acquire — so user memory survives sandbox TTL eviction and replica restarts.
         */
        public static class Snapshot {
            /** Whether to persist sandbox workspaces to durable storage. */
            private boolean enabled = true;

            /** Storage table for workspace tar archives (JDBC/Postgres backend). */
            private String table = "agentscope_sandbox_snapshots";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getTable() {
                return table;
            }

            public void setTable(String table) {
                this.table = table;
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
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

        public String getIsolationScope() {
            return isolationScope;
        }

        public void setIsolationScope(String isolationScope) {
            this.isolationScope = isolationScope;
        }

        public Long getMemoryLimitBytes() {
            return memoryLimitBytes;
        }

        public void setMemoryLimitBytes(Long memoryLimitBytes) {
            this.memoryLimitBytes = memoryLimitBytes;
        }

        public Long getCpuCount() {
            return cpuCount;
        }

        public void setCpuCount(Long cpuCount) {
            this.cpuCount = cpuCount;
        }

        public int getIdleTtlSeconds() {
            return idleTtlSeconds;
        }

        public void setIdleTtlSeconds(int idleTtlSeconds) {
            this.idleTtlSeconds = idleTtlSeconds;
        }

        public String getCubeApiUrl() {
            return cubeApiUrl;
        }

        public void setCubeApiUrl(String cubeApiUrl) {
            this.cubeApiUrl = cubeApiUrl;
        }

        public String getCubeApiKey() {
            return cubeApiKey;
        }

        public void setCubeApiKey(String cubeApiKey) {
            this.cubeApiKey = cubeApiKey;
        }

        public String getCubeTemplateId() {
            return cubeTemplateId;
        }

        public void setCubeTemplateId(String cubeTemplateId) {
            this.cubeTemplateId = cubeTemplateId;
        }

        public String getCubeDomain() {
            return cubeDomain;
        }

        public void setCubeDomain(String cubeDomain) {
            this.cubeDomain = cubeDomain;
        }

        public String getCubeEnvdHostPattern() {
            return cubeEnvdHostPattern;
        }

        public void setCubeEnvdHostPattern(String cubeEnvdHostPattern) {
            this.cubeEnvdHostPattern = cubeEnvdHostPattern;
        }

        public int getCubeSandboxTimeoutSeconds() {
            return cubeSandboxTimeoutSeconds;
        }

        public void setCubeSandboxTimeoutSeconds(int cubeSandboxTimeoutSeconds) {
            this.cubeSandboxTimeoutSeconds = cubeSandboxTimeoutSeconds;
        }
    }

    /** Per-org request rate limit (sliding/fixed window). */
    public static class RateLimit {
        private int maxRequests = 60;
        private int windowSeconds = 60;

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Default agent configuration. */
    public static class Agent {
        private String name = "assistant";
        private String sysPrompt =
                "You are a helpful enterprise AI assistant. Answer concisely and accurately.";
        private int maxIters = 10;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSysPrompt() {
            return sysPrompt;
        }

        public void setSysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
        }

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }
    }
}
