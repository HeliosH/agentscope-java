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

import java.util.List;
import java.util.Map;
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
    @NestedConfigurationProperty private final Ltm ltm = new Ltm();

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

    public Ltm getLtm() {
        return ltm;
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

        /** Backend type: {@code "cube"} (private deployment), {@code "docker"} (local), {@code "e2b"} (official hosted). */
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

        /**
         * Whether to skip TLS certificate/hostname verification for Cube envd/platform HTTPS calls.
         * Intended only for private dev/test Cube deployments with self-signed certificates.
         */
        private boolean cubeInsecureSkipTlsVerify = false;

        // --- E2B-specific fields ---

        /** E2B API key (official hosted service). Required when type=e2b. */
        private String e2bApiKey;

        /**
         * E2B API base URL (defaults to {@code https://api.e2b.app}). Override only for an
         * E2B-compatible private deployment.
         */
        private String e2bApiBaseUrl;

        /** E2B template ID (defaults to the official {@code "base"} template when null). */
        private String e2bTemplateId;

        /** E2B sandbox domain (defaults to {@code e2b.app}). Override for private deployments. */
        private String e2bDomain;

        /** E2B sandbox idle timeout in seconds (defaults to 300). */
        private int e2bSandboxTimeoutSeconds = 300;

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

            /**
             * Durable storage backend: {@code pg} (Postgres BYTEA, dev/H2 fallback) or {@code minio}
             * (S3-compatible object storage, production). Defaults to {@code pg} for zero-infra dev.
             */
            private String backend = "pg";

            /** Storage table for workspace tar archives (JDBC/Postgres backend). */
            private String table = "agentscope_sandbox_snapshots";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBackend() {
                return backend;
            }

            public void setBackend(String backend) {
                this.backend = backend;
            }

            public String getTable() {
                return table;
            }

            public void setTable(String table) {
                this.table = table;
            }
        }

        /**
         * MinIO/S3 object-storage configuration for the {@code minio} snapshot backend. Only used
         * when {@code snapshot.backend=minio}. Leave disabled to fall back to Postgres BYTEA.
         */
        @NestedConfigurationProperty private final Minio minio = new Minio();

        public Minio getMinio() {
            return minio;
        }

        public static class Minio {
            /** MinIO/S3 endpoint URL (e.g. {@code http://localhost:9000}). */
            private String endpoint = "http://localhost:9000";

            /** Access key. */
            private String accessKey = "minioadmin";

            /** Secret key. */
            private String secretKey = "minioadmin";

            /** Bucket name (created if absent). */
            private String bucket = "agentscope-saas";

            /** Object key prefix (e.g. {@code "snapshots/"}). */
            private String keyPrefix = "snapshots/";

            /** AWS region (optional, for non-MinIO S3). */
            private String region;

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getAccessKey() {
                return accessKey;
            }

            public void setAccessKey(String accessKey) {
                this.accessKey = accessKey;
            }

            public String getSecretKey() {
                return secretKey;
            }

            public void setSecretKey(String secretKey) {
                this.secretKey = secretKey;
            }

            public String getBucket() {
                return bucket;
            }

            public void setBucket(String bucket) {
                this.bucket = bucket;
            }

            public String getKeyPrefix() {
                return keyPrefix;
            }

            public void setKeyPrefix(String keyPrefix) {
                this.keyPrefix = keyPrefix;
            }

            public String getRegion() {
                return region;
            }

            public void setRegion(String region) {
                this.region = region;
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

        public boolean isCubeInsecureSkipTlsVerify() {
            return cubeInsecureSkipTlsVerify;
        }

        public void setCubeInsecureSkipTlsVerify(boolean cubeInsecureSkipTlsVerify) {
            this.cubeInsecureSkipTlsVerify = cubeInsecureSkipTlsVerify;
        }

        public String getE2bApiKey() {
            return e2bApiKey;
        }

        public void setE2bApiKey(String e2bApiKey) {
            this.e2bApiKey = e2bApiKey;
        }

        public String getE2bApiBaseUrl() {
            return e2bApiBaseUrl;
        }

        public void setE2bApiBaseUrl(String e2bApiBaseUrl) {
            this.e2bApiBaseUrl = e2bApiBaseUrl;
        }

        public String getE2bTemplateId() {
            return e2bTemplateId;
        }

        public void setE2bTemplateId(String e2bTemplateId) {
            this.e2bTemplateId = e2bTemplateId;
        }

        public String getE2bDomain() {
            return e2bDomain;
        }

        public void setE2bDomain(String e2bDomain) {
            this.e2bDomain = e2bDomain;
        }

        public int getE2bSandboxTimeoutSeconds() {
            return e2bSandboxTimeoutSeconds;
        }

        public void setE2bSandboxTimeoutSeconds(int e2bSandboxTimeoutSeconds) {
            this.e2bSandboxTimeoutSeconds = e2bSandboxTimeoutSeconds;
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
        @NestedConfigurationProperty private final Skills skills = new Skills();
        @NestedConfigurationProperty private final Permission permission = new Permission();

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

        public Skills getSkills() {
            return skills;
        }

        public Permission getPermission() {
            return permission;
        }
    }

    /**
     * Skill self-evolution settings. When enabled and a workspace filesystem is present (sandbox
     * on), the agent can propose/promote skills from its runs and a background curator consolidates
     * them — mirroring QwenPaw's self-evolving skill tree, per-user via the sandbox workspace.
     */
    public static class Skills {
        private boolean selfEvolution = true;

        public boolean isSelfEvolution() {
            return selfEvolution;
        }

        public void setSelfEvolution(boolean selfEvolution) {
            this.selfEvolution = selfEvolution;
        }
    }

    /**
     * Permission engine (tool_guard) settings. Configures the framework's {@code
     * PermissionContextState} with tool-name-level ALLOW/ASK/DENY rules — mirroring QwenPaw's
     * tool_guard so read-only tools are auto-allowed, mutating/shell tools require confirmation
     * (ASK), and explicitly denied tools are blocked. Mode {@code DEFAULT} pauses on ASK for HITL
     * (the frontend renders a confirm card and resumes); {@code DONT_ASK} demotes ASK to DENY for
     * unattended runs.
     */
    public static class Permission {
        private String mode = "DEFAULT";
        private List<String> allowTools =
                List.of(
                        "readFile",
                        "grepFiles",
                        "glob_files",
                        "listFiles",
                        "memory_get",
                        "memory_search",
                        "session_search");
        private List<String> askTools =
                List.of("writeFile", "editFile", "propose_skill", "skill_manage", "execute");
        private List<String> denyTools = List.of();

        /**
         * Parameter-level ASK rules: tool name &#8594; regex. When a tool call's {@code command}
         * argument matches the regex, the call is gated with ASK (HITL confirm card); non-matching
         * invocations fall through to the tool-name-level allow/ask rules. This enables
         * "ask only for dangerous commands" policies (e.g. {@code execute: "rm -rf|mkfs|dd if="})
         * instead of gating the whole tool. Only tools with a {@code command} argument (currently
         * {@code execute}) honor parameter-level rules; others ignore the regex. Empty by default —
         * production that wants fine-grained shell gating overrides this; the e2e/dev profiles keep
         * the tool-name-level allow-all for unattended runs.
         */
        private Map<String, String> askRules = Map.of();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public List<String> getAllowTools() {
            return allowTools;
        }

        public void setAllowTools(List<String> allowTools) {
            this.allowTools = allowTools;
        }

        public List<String> getAskTools() {
            return askTools;
        }

        public void setAskTools(List<String> askTools) {
            this.askTools = askTools;
        }

        public List<String> getDenyTools() {
            return denyTools;
        }

        public void setDenyTools(List<String> denyTools) {
            this.denyTools = denyTools;
        }

        public Map<String, String> getAskRules() {
            return askRules;
        }

        public void setAskRules(Map<String, String> askRules) {
            this.askRules = askRules == null ? Map.of() : askRules;
        }
    }

    /**
     * Long-term memory (Mem0) configuration. When enabled, the {@code
     * SaasLongTermMemoryMiddleware} retrieves and records semantically relevant memories per
     * tenant (userId + orgId metadata) around each agent call. When disabled (the default), the
     * agent falls back to MEMORY.md + snapshot persistence — no Mem0 dependency is exercised.
     */
    public static class Ltm {
        /** Master switch. Defaults to off so the platform runs with zero external deps. */
        private boolean enabled = false;

        /** Mem0 API base URL (e.g. {@code https://api.mem0.ai} or a self-hosted endpoint). */
        private String mem0BaseUrl;

        /** Mem0 API key (optional for self-hosted deployments without auth). */
        private String mem0ApiKey;

        /** Mem0 API type: {@code PLATFORM} (default) or {@code SELF_HOSTED}. */
        private String mem0ApiType = "PLATFORM";

        /** Mem0 request timeout in seconds (default 60). */
        private int timeoutSeconds = 60;

        /** Max memories to retrieve per call (default 5). */
        private int topK = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMem0BaseUrl() {
            return mem0BaseUrl;
        }

        public void setMem0BaseUrl(String mem0BaseUrl) {
            this.mem0BaseUrl = mem0BaseUrl;
        }

        public String getMem0ApiKey() {
            return mem0ApiKey;
        }

        public void setMem0ApiKey(String mem0ApiKey) {
            this.mem0ApiKey = mem0ApiKey;
        }

        public String getMem0ApiType() {
            return mem0ApiType;
        }

        public void setMem0ApiType(String mem0ApiType) {
            this.mem0ApiType = mem0ApiType;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }
}
