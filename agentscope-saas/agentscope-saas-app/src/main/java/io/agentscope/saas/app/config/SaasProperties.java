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
    @NestedConfigurationProperty private final FileStore fileStore = new FileStore();
    @NestedConfigurationProperty private final Sandbox sandbox = new Sandbox();
    @NestedConfigurationProperty private final RateLimit rateLimit = new RateLimit();
    @NestedConfigurationProperty private final Agent agent = new Agent();
    @NestedConfigurationProperty private final Orchestration orchestration = new Orchestration();
    @NestedConfigurationProperty private final Subagents subagents = new Subagents();
    @NestedConfigurationProperty private final Ltm ltm = new Ltm();
    @NestedConfigurationProperty private final Degradation degradation = new Degradation();

    public Model getModel() {
        return model;
    }

    public Redis getRedis() {
        return redis;
    }

    public FileStore getFileStore() {
        return fileStore;
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

    public Orchestration getOrchestration() {
        return orchestration;
    }

    public Subagents getSubagents() {
        return subagents;
    }

    public Ltm getLtm() {
        return ltm;
    }

    public Degradation getDegradation() {
        return degradation;
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

    /** Durable object storage for user/workspace files. */
    public static class FileStore {
        private static final long MIB = 1024L * 1024L;
        private static final long GIB = 1024L * MIB;

        /** Whether to catalog workspace file writes/downloads into durable file metadata tables. */
        private boolean enabled = true;

        /** Object backend: {@code pg} for local fallback or {@code minio} for production. */
        private String backend = "pg";

        /** PostgreSQL/H2 BYTEA fallback table used when backend=pg. */
        private String table = "file_object_blobs";

        /** Prefix used when generating object keys. */
        private String objectKeyPrefix = "files/";

        /** Maximum size accepted for one uploaded or projected file. */
        private long maxFileBytes = 32L * MIB;

        /** Maximum active logical bytes for one user; zero disables the quota. */
        private long maxUserBytes = 5L * GIB;

        /** Maximum active logical bytes for one org; zero disables the quota. */
        private long maxOrgBytes = 100L * GIB;

        /** Executable containers blocked before they enter the workspace. */
        private List<String> blockedExtensions = List.of("exe", "dll", "com", "msi", "apk", "dmg");

        /** Declared media types rejected before body processing. */
        private List<String> blockedContentTypes =
                List.of(
                        "application/x-msdownload",
                        "application/x-msdos-program",
                        "application/vnd.android.package-archive");

        /** Retention period for soft-deleted logical files. */
        private int deletedRetentionDays = 30;

        /** Number of immutable versions retained per active logical file. */
        private int maxVersionsPerFile = 20;

        private boolean gcEnabled = true;
        private long gcFixedDelaySeconds = 3600L;
        private int gcBatchSize = 100;
        private int gcMaxAttempts = 10;

        @NestedConfigurationProperty private final Minio minio = new Minio();
        @NestedConfigurationProperty private final Antivirus antivirus = new Antivirus();

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

        public String getObjectKeyPrefix() {
            return objectKeyPrefix;
        }

        public void setObjectKeyPrefix(String objectKeyPrefix) {
            this.objectKeyPrefix = objectKeyPrefix;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public long getMaxUserBytes() {
            return maxUserBytes;
        }

        public void setMaxUserBytes(long maxUserBytes) {
            this.maxUserBytes = maxUserBytes;
        }

        public long getMaxOrgBytes() {
            return maxOrgBytes;
        }

        public void setMaxOrgBytes(long maxOrgBytes) {
            this.maxOrgBytes = maxOrgBytes;
        }

        public List<String> getBlockedExtensions() {
            return blockedExtensions;
        }

        public void setBlockedExtensions(List<String> blockedExtensions) {
            this.blockedExtensions = blockedExtensions == null ? List.of() : blockedExtensions;
        }

        public List<String> getBlockedContentTypes() {
            return blockedContentTypes;
        }

        public void setBlockedContentTypes(List<String> blockedContentTypes) {
            this.blockedContentTypes =
                    blockedContentTypes == null ? List.of() : blockedContentTypes;
        }

        public int getDeletedRetentionDays() {
            return deletedRetentionDays;
        }

        public void setDeletedRetentionDays(int deletedRetentionDays) {
            this.deletedRetentionDays = deletedRetentionDays;
        }

        public int getMaxVersionsPerFile() {
            return maxVersionsPerFile;
        }

        public void setMaxVersionsPerFile(int maxVersionsPerFile) {
            this.maxVersionsPerFile = maxVersionsPerFile;
        }

        public boolean isGcEnabled() {
            return gcEnabled;
        }

        public void setGcEnabled(boolean gcEnabled) {
            this.gcEnabled = gcEnabled;
        }

        public long getGcFixedDelaySeconds() {
            return gcFixedDelaySeconds;
        }

        public void setGcFixedDelaySeconds(long gcFixedDelaySeconds) {
            this.gcFixedDelaySeconds = gcFixedDelaySeconds;
        }

        public int getGcBatchSize() {
            return gcBatchSize;
        }

        public void setGcBatchSize(int gcBatchSize) {
            this.gcBatchSize = gcBatchSize;
        }

        public int getGcMaxAttempts() {
            return gcMaxAttempts;
        }

        public void setGcMaxAttempts(int gcMaxAttempts) {
            this.gcMaxAttempts = gcMaxAttempts;
        }

        public Minio getMinio() {
            return minio;
        }

        public Antivirus getAntivirus() {
            return antivirus;
        }

        public static class Antivirus {
            private boolean enabled = false;
            private String host = "localhost";
            private int port = 3310;
            private int timeoutSeconds = 15;
            private boolean failClosed = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public int getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }

            public boolean isFailClosed() {
                return failClosed;
            }

            public void setFailClosed(boolean failClosed) {
                this.failClosed = failClosed;
            }
        }

        public static class Minio {
            private String endpoint = "http://localhost:9000";
            private String accessKey = "minioadmin";
            private String secretKey = "minioadmin";
            private String bucket = "agentscope-saas";
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

            public String getRegion() {
                return region;
            }

            public void setRegion(String region) {
                this.region = region;
            }
        }
    }

    /** Sandbox execution configuration. When disabled, the shell tool is removed and no
     * sandbox filesystem is configured — the agent runs without command execution capability. */
    public static class Sandbox {
        /** Whether sandbox-backed command execution is enabled. */
        private boolean enabled = false;

        /**
         * Backend type: {@code "cube"} (private deployment), {@code "docker"} (local), {@code
         * "e2b"} (official hosted), {@code "opensandbox"} (OpenSandbox lifecycle API).
         */
        private String type = "cube";

        /** Docker image for sandbox containers (type=docker only). */
        private String image = "ubuntu:22.04";

        /** Workspace root path inside the sandbox. Defaults differ by type: /workspace for docker,
         * /home/user for cube, /home/user/workspace for e2b. */
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

        /** Maximum seconds a request may wait for the same sandbox execution slot. <=0 disables. */
        private int executionGuardMaxWaitSeconds = 60;

        /** Redis execution guard lease TTL in seconds. Must exceed worst-case agent call duration. */
        private int executionGuardLeaseTtlSeconds = 1800;

        /** Redis execution guard retry interval in milliseconds. */
        private int executionGuardRetryIntervalMillis = 500;

        /** Enables system reconciliation of leaked sandbox tracking/backend resources. */
        private boolean reconciliationEnabled = true;

        /** Fixed delay between reconciliation scans in seconds. */
        private long reconciliationFixedDelaySeconds = 300L;

        /** Maximum number of sandbox rows reconciled per scan. */
        private int reconciliationBatchSize = 100;

        /** Additional grace before an expired active row is system-evicted. */
        private long reconciliationActiveGraceSeconds = 120L;

        /** Maximum provider backend release attempts before leaving the row for review. */
        private int backendReleaseMaxAttempts = 5;

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

        // --- OpenSandbox-specific fields ---

        /** OpenSandbox lifecycle API base URL. Both root and /v1 prefixes are supported. */
        private String openSandboxApiBaseUrl = "http://localhost:8080/v1";

        /** Optional OpenSandbox lifecycle API key (OPEN-SANDBOX-API-KEY). */
        private String openSandboxApiKey;

        /** Optional execd token (X-EXECD-ACCESS-TOKEN) when execd auth is enabled. */
        private String openSandboxExecdAccessToken;

        /** Container image OpenSandbox should provision for assistant task sandboxes. */
        private String openSandboxImage;

        /** OpenSandbox resource limit string for CPU (for example 1, 500m). */
        private String openSandboxCpuLimit = "1";

        /** OpenSandbox resource limit string for memory (for example 1Gi, 512Mi). */
        private String openSandboxMemoryLimit = "1Gi";

        /** OpenSandbox sandbox timeout in seconds. */
        private int openSandboxSandboxTimeoutSeconds = 300;

        /** Maximum seconds to wait for OpenSandbox async provisioning to reach Running. */
        private int openSandboxWaitTimeoutSeconds = 120;

        /** Port used by OpenSandbox execd inside the sandbox. */
        private int openSandboxExecdPort = 44772;

        /** Default command execution timeout in seconds. */
        private int openSandboxDefaultExecTimeoutSeconds = 120;

        /** HTTP connect timeout in seconds for OpenSandbox calls. */
        private int openSandboxConnectTimeoutSeconds = 30;

        /** HTTP read timeout in seconds for OpenSandbox calls. */
        private int openSandboxReadTimeoutSeconds = 300;

        /** Max retries for OpenSandbox lifecycle GET/POST calls. */
        private int openSandboxMaxRetries = 3;

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

        public int getExecutionGuardMaxWaitSeconds() {
            return executionGuardMaxWaitSeconds;
        }

        public void setExecutionGuardMaxWaitSeconds(int executionGuardMaxWaitSeconds) {
            this.executionGuardMaxWaitSeconds = executionGuardMaxWaitSeconds;
        }

        public int getExecutionGuardLeaseTtlSeconds() {
            return executionGuardLeaseTtlSeconds;
        }

        public void setExecutionGuardLeaseTtlSeconds(int executionGuardLeaseTtlSeconds) {
            this.executionGuardLeaseTtlSeconds = executionGuardLeaseTtlSeconds;
        }

        public int getExecutionGuardRetryIntervalMillis() {
            return executionGuardRetryIntervalMillis;
        }

        public void setExecutionGuardRetryIntervalMillis(int executionGuardRetryIntervalMillis) {
            this.executionGuardRetryIntervalMillis = executionGuardRetryIntervalMillis;
        }

        public boolean isReconciliationEnabled() {
            return reconciliationEnabled;
        }

        public void setReconciliationEnabled(boolean reconciliationEnabled) {
            this.reconciliationEnabled = reconciliationEnabled;
        }

        public long getReconciliationFixedDelaySeconds() {
            return reconciliationFixedDelaySeconds;
        }

        public void setReconciliationFixedDelaySeconds(long reconciliationFixedDelaySeconds) {
            this.reconciliationFixedDelaySeconds = reconciliationFixedDelaySeconds;
        }

        public int getReconciliationBatchSize() {
            return reconciliationBatchSize;
        }

        public void setReconciliationBatchSize(int reconciliationBatchSize) {
            this.reconciliationBatchSize = reconciliationBatchSize;
        }

        public long getReconciliationActiveGraceSeconds() {
            return reconciliationActiveGraceSeconds;
        }

        public void setReconciliationActiveGraceSeconds(long reconciliationActiveGraceSeconds) {
            this.reconciliationActiveGraceSeconds = reconciliationActiveGraceSeconds;
        }

        public int getBackendReleaseMaxAttempts() {
            return backendReleaseMaxAttempts;
        }

        public void setBackendReleaseMaxAttempts(int backendReleaseMaxAttempts) {
            this.backendReleaseMaxAttempts = backendReleaseMaxAttempts;
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

        public String getOpenSandboxApiBaseUrl() {
            return openSandboxApiBaseUrl;
        }

        public void setOpenSandboxApiBaseUrl(String openSandboxApiBaseUrl) {
            this.openSandboxApiBaseUrl = openSandboxApiBaseUrl;
        }

        public String getOpenSandboxApiKey() {
            return openSandboxApiKey;
        }

        public void setOpenSandboxApiKey(String openSandboxApiKey) {
            this.openSandboxApiKey = openSandboxApiKey;
        }

        public String getOpenSandboxExecdAccessToken() {
            return openSandboxExecdAccessToken;
        }

        public void setOpenSandboxExecdAccessToken(String openSandboxExecdAccessToken) {
            this.openSandboxExecdAccessToken = openSandboxExecdAccessToken;
        }

        public String getOpenSandboxImage() {
            return openSandboxImage;
        }

        public void setOpenSandboxImage(String openSandboxImage) {
            this.openSandboxImage = openSandboxImage;
        }

        public String getOpenSandboxCpuLimit() {
            return openSandboxCpuLimit;
        }

        public void setOpenSandboxCpuLimit(String openSandboxCpuLimit) {
            this.openSandboxCpuLimit = openSandboxCpuLimit;
        }

        public String getOpenSandboxMemoryLimit() {
            return openSandboxMemoryLimit;
        }

        public void setOpenSandboxMemoryLimit(String openSandboxMemoryLimit) {
            this.openSandboxMemoryLimit = openSandboxMemoryLimit;
        }

        public int getOpenSandboxSandboxTimeoutSeconds() {
            return openSandboxSandboxTimeoutSeconds;
        }

        public void setOpenSandboxSandboxTimeoutSeconds(int openSandboxSandboxTimeoutSeconds) {
            this.openSandboxSandboxTimeoutSeconds = openSandboxSandboxTimeoutSeconds;
        }

        public int getOpenSandboxWaitTimeoutSeconds() {
            return openSandboxWaitTimeoutSeconds;
        }

        public void setOpenSandboxWaitTimeoutSeconds(int openSandboxWaitTimeoutSeconds) {
            this.openSandboxWaitTimeoutSeconds = openSandboxWaitTimeoutSeconds;
        }

        public int getOpenSandboxExecdPort() {
            return openSandboxExecdPort;
        }

        public void setOpenSandboxExecdPort(int openSandboxExecdPort) {
            this.openSandboxExecdPort = openSandboxExecdPort;
        }

        public int getOpenSandboxDefaultExecTimeoutSeconds() {
            return openSandboxDefaultExecTimeoutSeconds;
        }

        public void setOpenSandboxDefaultExecTimeoutSeconds(
                int openSandboxDefaultExecTimeoutSeconds) {
            this.openSandboxDefaultExecTimeoutSeconds = openSandboxDefaultExecTimeoutSeconds;
        }

        public int getOpenSandboxConnectTimeoutSeconds() {
            return openSandboxConnectTimeoutSeconds;
        }

        public void setOpenSandboxConnectTimeoutSeconds(int openSandboxConnectTimeoutSeconds) {
            this.openSandboxConnectTimeoutSeconds = openSandboxConnectTimeoutSeconds;
        }

        public int getOpenSandboxReadTimeoutSeconds() {
            return openSandboxReadTimeoutSeconds;
        }

        public void setOpenSandboxReadTimeoutSeconds(int openSandboxReadTimeoutSeconds) {
            this.openSandboxReadTimeoutSeconds = openSandboxReadTimeoutSeconds;
        }

        public int getOpenSandboxMaxRetries() {
            return openSandboxMaxRetries;
        }

        public void setOpenSandboxMaxRetries(int openSandboxMaxRetries) {
            this.openSandboxMaxRetries = openSandboxMaxRetries;
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

        /** Enables the framework Todo tools for explicit task tracking. */
        private boolean taskListEnabled = true;

        /** Enables persistent, permission-enforced plan mode for complex work. */
        private boolean planModeEnabled = true;

        @NestedConfigurationProperty private final Conversation conversation = new Conversation();
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

        public boolean isTaskListEnabled() {
            return taskListEnabled;
        }

        public void setTaskListEnabled(boolean taskListEnabled) {
            this.taskListEnabled = taskListEnabled;
        }

        public boolean isPlanModeEnabled() {
            return planModeEnabled;
        }

        public void setPlanModeEnabled(boolean planModeEnabled) {
            this.planModeEnabled = planModeEnabled;
        }

        public Conversation getConversation() {
            return conversation;
        }

        public Skills getSkills() {
            return skills;
        }

        public Permission getPermission() {
            return permission;
        }
    }

    /** Background subagent execution backend selected once for an application deployment. */
    public static class Subagents {
        private String executionMode = "workspace";
        private int maxDepth = 3;
        private int maxChildrenPerAgent = 8;
        private int maxTasksPerRun = 32;

        public String getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(String executionMode) {
            this.executionMode = executionMode;
        }

        public boolean isDurable() {
            return "durable".equalsIgnoreCase(executionMode);
        }

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public int getMaxChildrenPerAgent() {
            return maxChildrenPerAgent;
        }

        public void setMaxChildrenPerAgent(int maxChildrenPerAgent) {
            this.maxChildrenPerAgent = maxChildrenPerAgent;
        }

        public int getMaxTasksPerRun() {
            return maxTasksPerRun;
        }

        public void setMaxTasksPerRun(int maxTasksPerRun) {
            this.maxTasksPerRun = maxTasksPerRun;
        }
    }

    /** Durable Run control-plane settings. Scheduler and durable subagents are phased in separately. */
    public static class Orchestration {
        /** Creates a persistent Run and root TaskNode for every chat request. */
        private boolean enabled = true;

        /** Reserved for the later lease-based scheduler rollout. */
        private boolean schedulerEnabled = false;

        /** Enables structured planning once plan publishing is wired. */
        private boolean plannerEnabled = false;

        /** Publishes the transactional event Outbox with lease-based, at-least-once delivery. */
        private boolean outboxEnabled = true;

        private long outboxFixedDelayMillis = 1000;
        private int outboxBatchSize = 100;
        private long outboxLeaseSeconds = 30;
        private int outboxMaxAttempts = 10;
        private long outboxRetryBaseSeconds = 2;
        private long outboxRetryMaxSeconds = 300;

        private int schedulerBatchSize = 16;
        private long schedulerLeaseSeconds = 60;
        private long schedulerRecoveryFixedDelaySeconds = 20;
        private long schedulerRetryMaxSeconds = 300;
        private long schedulerPollMillis = 1000;
        private long schedulerHeartbeatSeconds = 20;
        private int workerConcurrency = 4;
        private long workerExecutionTimeoutSeconds = 900;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSchedulerEnabled() {
            return schedulerEnabled;
        }

        public void setSchedulerEnabled(boolean schedulerEnabled) {
            this.schedulerEnabled = schedulerEnabled;
        }

        public boolean isPlannerEnabled() {
            return plannerEnabled;
        }

        public void setPlannerEnabled(boolean plannerEnabled) {
            this.plannerEnabled = plannerEnabled;
        }

        public boolean isOutboxEnabled() {
            return outboxEnabled;
        }

        public void setOutboxEnabled(boolean outboxEnabled) {
            this.outboxEnabled = outboxEnabled;
        }

        public long getOutboxFixedDelayMillis() {
            return outboxFixedDelayMillis;
        }

        public void setOutboxFixedDelayMillis(long outboxFixedDelayMillis) {
            this.outboxFixedDelayMillis = outboxFixedDelayMillis;
        }

        public int getOutboxBatchSize() {
            return outboxBatchSize;
        }

        public void setOutboxBatchSize(int outboxBatchSize) {
            this.outboxBatchSize = outboxBatchSize;
        }

        public long getOutboxLeaseSeconds() {
            return outboxLeaseSeconds;
        }

        public void setOutboxLeaseSeconds(long outboxLeaseSeconds) {
            this.outboxLeaseSeconds = outboxLeaseSeconds;
        }

        public int getOutboxMaxAttempts() {
            return outboxMaxAttempts;
        }

        public void setOutboxMaxAttempts(int outboxMaxAttempts) {
            this.outboxMaxAttempts = outboxMaxAttempts;
        }

        public long getOutboxRetryBaseSeconds() {
            return outboxRetryBaseSeconds;
        }

        public void setOutboxRetryBaseSeconds(long outboxRetryBaseSeconds) {
            this.outboxRetryBaseSeconds = outboxRetryBaseSeconds;
        }

        public long getOutboxRetryMaxSeconds() {
            return outboxRetryMaxSeconds;
        }

        public void setOutboxRetryMaxSeconds(long outboxRetryMaxSeconds) {
            this.outboxRetryMaxSeconds = outboxRetryMaxSeconds;
        }

        public int getSchedulerBatchSize() {
            return schedulerBatchSize;
        }

        public void setSchedulerBatchSize(int schedulerBatchSize) {
            this.schedulerBatchSize = schedulerBatchSize;
        }

        public long getSchedulerLeaseSeconds() {
            return schedulerLeaseSeconds;
        }

        public void setSchedulerLeaseSeconds(long schedulerLeaseSeconds) {
            this.schedulerLeaseSeconds = schedulerLeaseSeconds;
        }

        public long getSchedulerRecoveryFixedDelaySeconds() {
            return schedulerRecoveryFixedDelaySeconds;
        }

        public void setSchedulerRecoveryFixedDelaySeconds(long schedulerRecoveryFixedDelaySeconds) {
            this.schedulerRecoveryFixedDelaySeconds = schedulerRecoveryFixedDelaySeconds;
        }

        public long getSchedulerRetryMaxSeconds() {
            return schedulerRetryMaxSeconds;
        }

        public void setSchedulerRetryMaxSeconds(long schedulerRetryMaxSeconds) {
            this.schedulerRetryMaxSeconds = schedulerRetryMaxSeconds;
        }

        public long getSchedulerPollMillis() {
            return schedulerPollMillis;
        }

        public void setSchedulerPollMillis(long schedulerPollMillis) {
            this.schedulerPollMillis = schedulerPollMillis;
        }

        public long getSchedulerHeartbeatSeconds() {
            return schedulerHeartbeatSeconds;
        }

        public void setSchedulerHeartbeatSeconds(long schedulerHeartbeatSeconds) {
            this.schedulerHeartbeatSeconds = schedulerHeartbeatSeconds;
        }

        public int getWorkerConcurrency() {
            return workerConcurrency;
        }

        public void setWorkerConcurrency(int workerConcurrency) {
            this.workerConcurrency = workerConcurrency;
        }

        public long getWorkerExecutionTimeoutSeconds() {
            return workerExecutionTimeoutSeconds;
        }

        public void setWorkerExecutionTimeoutSeconds(long workerExecutionTimeoutSeconds) {
            this.workerExecutionTimeoutSeconds = workerExecutionTimeoutSeconds;
        }
    }

    /** Bounded model context for long-running sessions. Full history remains in PostgreSQL. */
    public static class Conversation {
        private boolean compactionEnabled = true;
        private int maxContextTokens = 32_000;
        private int compactionTriggerMessages = 60;
        private int compactionTriggerTokens = 24_000;
        private int compactionKeepMessages = 20;
        private int compactionKeepTokens = 8_000;
        private int truncateTriggerMessages = 30;
        private int truncateTriggerTokens = 12_000;
        private int truncateMaxArgumentLength = 2_000;

        public boolean isCompactionEnabled() {
            return compactionEnabled;
        }

        public void setCompactionEnabled(boolean compactionEnabled) {
            this.compactionEnabled = compactionEnabled;
        }

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }

        public int getCompactionTriggerMessages() {
            return compactionTriggerMessages;
        }

        public void setCompactionTriggerMessages(int compactionTriggerMessages) {
            this.compactionTriggerMessages = compactionTriggerMessages;
        }

        public int getCompactionTriggerTokens() {
            return compactionTriggerTokens;
        }

        public void setCompactionTriggerTokens(int compactionTriggerTokens) {
            this.compactionTriggerTokens = compactionTriggerTokens;
        }

        public int getCompactionKeepMessages() {
            return compactionKeepMessages;
        }

        public void setCompactionKeepMessages(int compactionKeepMessages) {
            this.compactionKeepMessages = compactionKeepMessages;
        }

        public int getCompactionKeepTokens() {
            return compactionKeepTokens;
        }

        public void setCompactionKeepTokens(int compactionKeepTokens) {
            this.compactionKeepTokens = compactionKeepTokens;
        }

        public int getTruncateTriggerMessages() {
            return truncateTriggerMessages;
        }

        public void setTruncateTriggerMessages(int truncateTriggerMessages) {
            this.truncateTriggerMessages = truncateTriggerMessages;
        }

        public int getTruncateTriggerTokens() {
            return truncateTriggerTokens;
        }

        public void setTruncateTriggerTokens(int truncateTriggerTokens) {
            this.truncateTriggerTokens = truncateTriggerTokens;
        }

        public int getTruncateMaxArgumentLength() {
            return truncateMaxArgumentLength;
        }

        public void setTruncateMaxArgumentLength(int truncateMaxArgumentLength) {
            this.truncateMaxArgumentLength = truncateMaxArgumentLength;
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
                        "read_file",
                        "grep_files",
                        "glob_files",
                        "list_files",
                        "memory_get",
                        "memory_search",
                        "session_search");
        private List<String> askTools =
                List.of("write_file", "edit_file", "propose_skill", "skill_manage", "execute");
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

        /** Enables the background replay of pending/failed ledger rows into Mem0. */
        private boolean replayEnabled = true;

        /** Fixed delay between replay scans in seconds. */
        private long replayFixedDelaySeconds = 60L;

        /** Maximum number of ledger rows to replay in one scan. */
        private int replayBatchSize = 50;

        /** Maximum projection attempts before a row is left failed for operator review. */
        private int replayMaxAttempts = 10;

        /** Reclaims rows left in syncing state after a worker crash. */
        private long replayStaleSeconds = 300L;

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

        public boolean isReplayEnabled() {
            return replayEnabled;
        }

        public void setReplayEnabled(boolean replayEnabled) {
            this.replayEnabled = replayEnabled;
        }

        public long getReplayFixedDelaySeconds() {
            return replayFixedDelaySeconds;
        }

        public void setReplayFixedDelaySeconds(long replayFixedDelaySeconds) {
            this.replayFixedDelaySeconds = replayFixedDelaySeconds;
        }

        public int getReplayBatchSize() {
            return replayBatchSize;
        }

        public void setReplayBatchSize(int replayBatchSize) {
            this.replayBatchSize = replayBatchSize;
        }

        public int getReplayMaxAttempts() {
            return replayMaxAttempts;
        }

        public void setReplayMaxAttempts(int replayMaxAttempts) {
            this.replayMaxAttempts = replayMaxAttempts;
        }

        public long getReplayStaleSeconds() {
            return replayStaleSeconds;
        }

        public void setReplayStaleSeconds(long replayStaleSeconds) {
            this.replayStaleSeconds = replayStaleSeconds;
        }
    }

    /**
     * Runtime degradation policy. The default {@code warn} mode records dependency status and
     * exposes admin diagnostics without blocking local smoke tests. Production deployments can set
     * {@code chat-policy=block} to fail closed when required runtime dependencies are unhealthy.
     */
    public static class Degradation {
        /** Master switch for dependency probes and policy decisions. */
        private boolean enabled = true;

        /** One of: warn, block. Block prevents new chat runs when critical dependencies degrade. */
        private String chatPolicy = "warn";

        /** Seconds to cache probe results so every chat request does not hit infrastructure. */
        private int healthCacheTtlSeconds = 15;

        /**
         * Optional OpenSandbox health path appended to open-sandbox-api-base-url. Empty means
         * provider health is treated as unknown and never blocks by itself.
         */
        private String openSandboxHealthPath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChatPolicy() {
            return chatPolicy;
        }

        public void setChatPolicy(String chatPolicy) {
            this.chatPolicy = chatPolicy;
        }

        public int getHealthCacheTtlSeconds() {
            return healthCacheTtlSeconds;
        }

        public void setHealthCacheTtlSeconds(int healthCacheTtlSeconds) {
            this.healthCacheTtlSeconds = healthCacheTtlSeconds;
        }

        public String getOpenSandboxHealthPath() {
            return openSandboxHealthPath;
        }

        public void setOpenSandboxHealthPath(String openSandboxHealthPath) {
            this.openSandboxHealthPath = openSandboxHealthPath;
        }
    }
}
