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
package io.agentscope.saas.app.degradation;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.observability.AgentRunMetrics;
import io.agentscope.saas.storage.FileObjectStore;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

/**
 * Central runtime degradation policy for enterprise deployments.
 *
 * <p>The manager keeps dependency probes cached and translates degraded dependency state into a
 * single chat decision. Default mode is advisory ({@code warn}); production can switch to
 * {@code block} to fail closed before a sandbox task starts and risks losing workspace state.
 */
@Component
public class DegradationManager {

    private static final String HEALTHY = "healthy";
    private static final String DEGRADED = "degraded";
    private static final String DISABLED = "disabled";
    private static final String UNKNOWN = "unknown";

    private final SaasProperties properties;
    private final ObjectProvider<UnifiedJedis> jedisProvider;
    private final ObjectProvider<FileObjectStore> fileObjectStoreProvider;
    private final AgentRunMetrics metrics;
    private final HttpClient httpClient;

    private volatile Snapshot cached;

    public DegradationManager(
            SaasProperties properties,
            ObjectProvider<UnifiedJedis> jedisProvider,
            ObjectProvider<FileObjectStore> fileObjectStoreProvider,
            AgentRunMetrics metrics) {
        this.properties = properties;
        this.jedisProvider = jedisProvider;
        this.fileObjectStoreProvider = fileObjectStoreProvider;
        this.metrics = metrics != null ? metrics : AgentRunMetrics.noop();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Decision evaluateChat() {
        if (!properties.getDegradation().isEnabled()) {
            return Decision.allowed("disabled", "degradation policy disabled", List.of());
        }
        Snapshot snapshot = currentSnapshot(false);
        boolean blockPolicy =
                "block".equals(normalize(properties.getDegradation().getChatPolicy()));
        List<DependencyStatus> blockers =
                snapshot.statuses().stream()
                        .filter(status -> DEGRADED.equals(status.status()))
                        .filter(DependencyStatus::blocksChat)
                        .toList();
        if (blockPolicy && !blockers.isEmpty()) {
            metrics.recordDegradation("chat", DEGRADED, "blocked");
            String components =
                    String.join(
                            ", ",
                            blockers.stream().map(DependencyStatus::component).distinct().toList());
            return new Decision(
                    false,
                    "blocked",
                    "runtime dependency degraded: " + components,
                    snapshot.statuses(),
                    snapshot.checkedAt(),
                    snapshot.expiresAt());
        }
        if (!blockers.isEmpty()) {
            metrics.recordDegradation("chat", DEGRADED, "warn");
        }
        return new Decision(
                true,
                blockPolicy ? "allow" : "warn",
                blockers.isEmpty()
                        ? "runtime dependencies available"
                        : "degraded dependencies observed",
                snapshot.statuses(),
                snapshot.checkedAt(),
                snapshot.expiresAt());
    }

    public Decision currentStatus(boolean forceRefresh) {
        Snapshot snapshot = currentSnapshot(forceRefresh);
        return new Decision(
                true,
                "status",
                "runtime dependency status",
                snapshot.statuses(),
                snapshot.checkedAt(),
                snapshot.expiresAt());
    }

    private Snapshot currentSnapshot(boolean forceRefresh) {
        Instant now = Instant.now();
        Snapshot existing = cached;
        if (!forceRefresh && existing != null && existing.expiresAt().isAfter(now)) {
            return existing;
        }
        Snapshot refreshed = refresh(now);
        cached = refreshed;
        return refreshed;
    }

    private Snapshot refresh(Instant now) {
        List<DependencyStatus> statuses = new ArrayList<>();
        statuses.add(probeRedis(now));
        statuses.add(probeFileObjectStore(now));
        statuses.add(probeSandboxSnapshot(now));
        statuses.add(probeOpenSandbox(now));
        for (DependencyStatus status : statuses) {
            if (!HEALTHY.equals(status.status()) && !DISABLED.equals(status.status())) {
                metrics.recordDegradation(status.component(), status.status(), status.action());
            }
        }
        long ttl = Math.max(1, properties.getDegradation().getHealthCacheTtlSeconds());
        return new Snapshot(statuses, now, now.plusSeconds(ttl));
    }

    private DependencyStatus probeRedis(Instant checkedAt) {
        if (!properties.getRedis().isEnabled()) {
            return status(
                    "redis",
                    DISABLED,
                    "fallback_local",
                    "Redis disabled; local/in-memory fallbacks may be used where configured",
                    false,
                    checkedAt);
        }
        UnifiedJedis jedis = jedisProvider.getIfAvailable();
        if (jedis == null) {
            return status(
                    "redis",
                    DEGRADED,
                    "block_when_policy",
                    "Redis is enabled but no client bean is available",
                    true,
                    checkedAt);
        }
        try {
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                return status(
                        "redis",
                        DEGRADED,
                        "block_when_policy",
                        "Redis ping returned " + pong,
                        true,
                        checkedAt);
            }
            return status("redis", HEALTHY, "allow", "Redis ping succeeded", false, checkedAt);
        } catch (Exception e) {
            return status(
                    "redis",
                    DEGRADED,
                    "block_when_policy",
                    "Redis ping failed: " + concise(e),
                    true,
                    checkedAt);
        }
    }

    private DependencyStatus probeFileObjectStore(Instant checkedAt) {
        if (!properties.getFileStore().isEnabled()) {
            return status(
                    "file_object_store",
                    DISABLED,
                    "block_file_downloads",
                    "Durable file object store disabled",
                    properties.getSandbox().isEnabled(),
                    checkedAt);
        }
        FileObjectStore store = fileObjectStoreProvider.getIfAvailable();
        if (store == null) {
            return status(
                    "file_object_store",
                    DEGRADED,
                    "block_when_policy",
                    "No durable file object store bean is available",
                    true,
                    checkedAt);
        }
        try {
            store.healthCheck();
            return status(
                    "file_object_store",
                    HEALTHY,
                    "allow",
                    "File object store reachable: " + store.backend(),
                    false,
                    checkedAt);
        } catch (Exception e) {
            return status(
                    "file_object_store",
                    DEGRADED,
                    "block_when_policy",
                    "File object store check failed: " + concise(e),
                    true,
                    checkedAt);
        }
    }

    private DependencyStatus probeSandboxSnapshot(Instant checkedAt) {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        if (!sandbox.isEnabled()) {
            return status(
                    "sandbox_snapshot",
                    DISABLED,
                    "no_sandbox",
                    "Sandbox runtime disabled",
                    false,
                    checkedAt);
        }
        if (!sandbox.getSnapshot().isEnabled()) {
            return status(
                    "sandbox_snapshot",
                    DEGRADED,
                    "block_when_policy",
                    "Sandbox snapshots disabled; workspace and MEMORY.md are ephemeral",
                    true,
                    checkedAt);
        }
        String backend = normalize(sandbox.getSnapshot().getBackend());
        if (!"minio".equals(backend)) {
            return status(
                    "sandbox_snapshot",
                    HEALTHY,
                    "allow",
                    "Sandbox snapshot backend=" + backend,
                    false,
                    checkedAt);
        }
        try {
            SaasProperties.Sandbox.Minio minio = sandbox.getMinio();
            probeMinio(
                    minio.getEndpoint(),
                    minio.getAccessKey(),
                    minio.getSecretKey(),
                    minio.getRegion(),
                    minio.getBucket());
            return status(
                    "sandbox_snapshot",
                    HEALTHY,
                    "allow",
                    "Sandbox snapshot MinIO reachable",
                    false,
                    checkedAt);
        } catch (Exception e) {
            return status(
                    "sandbox_snapshot",
                    DEGRADED,
                    "block_when_policy",
                    "Sandbox snapshot MinIO check failed: " + concise(e),
                    true,
                    checkedAt);
        }
    }

    private DependencyStatus probeOpenSandbox(Instant checkedAt) {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        if (!sandbox.isEnabled()) {
            return status(
                    "opensandbox",
                    DISABLED,
                    "no_sandbox",
                    "Sandbox runtime disabled",
                    false,
                    checkedAt);
        }
        if (!"opensandbox".equals(normalize(sandbox.getType()))) {
            return status(
                    "opensandbox",
                    DISABLED,
                    "not_selected",
                    "Sandbox type is " + sandbox.getType(),
                    false,
                    checkedAt);
        }
        String healthPath = properties.getDegradation().getOpenSandboxHealthPath();
        if (healthPath == null || healthPath.isBlank()) {
            return status(
                    "opensandbox",
                    UNKNOWN,
                    "configure_health_path",
                    "OpenSandbox health path is not configured; runtime failures are handled during"
                            + " task execution",
                    false,
                    checkedAt);
        }
        try {
            URI uri = healthUri(sandbox.getOpenSandboxApiBaseUrl(), healthPath);
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
            if (sandbox.getOpenSandboxApiKey() != null
                    && !sandbox.getOpenSandboxApiKey().isBlank()) {
                builder.header("OPEN-SANDBOX-API-KEY", sandbox.getOpenSandboxApiKey());
            }
            HttpResponse<Void> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return status(
                        "opensandbox",
                        HEALTHY,
                        "allow",
                        "OpenSandbox health check returned HTTP " + response.statusCode(),
                        false,
                        checkedAt);
            }
            return status(
                    "opensandbox",
                    DEGRADED,
                    "block_when_policy",
                    "OpenSandbox health check returned HTTP " + response.statusCode(),
                    true,
                    checkedAt);
        } catch (Exception e) {
            return status(
                    "opensandbox",
                    DEGRADED,
                    "block_when_policy",
                    "OpenSandbox health check failed: " + concise(e),
                    true,
                    checkedAt);
        }
    }

    private void probeMinio(
            String endpoint, String accessKey, String secretKey, String region, String bucket)
            throws Exception {
        MinioClient.Builder builder =
                MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        builder.build().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    private static URI healthUri(String baseUrl, String healthPath) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String path = healthPath == null ? "" : healthPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static DependencyStatus status(
            String component,
            String status,
            String action,
            String message,
            boolean blocksChat,
            Instant checkedAt) {
        return new DependencyStatus(component, status, action, message, blocksChat, checkedAt);
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record Snapshot(
            List<DependencyStatus> statuses, Instant checkedAt, Instant expiresAt) {}

    public record DependencyStatus(
            String component,
            String status,
            String action,
            String message,
            boolean blocksChat,
            Instant checkedAt) {}

    public record Decision(
            boolean allowed,
            String action,
            String message,
            List<DependencyStatus> dependencies,
            Instant checkedAt,
            Instant expiresAt) {

        static Decision allowed(
                String action, String message, List<DependencyStatus> dependencies) {
            Instant now = Instant.now();
            return new Decision(true, action, message, dependencies, now, now);
        }
    }
}
