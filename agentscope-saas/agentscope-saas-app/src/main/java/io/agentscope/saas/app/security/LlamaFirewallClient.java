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
package io.agentscope.saas.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.LlamaFirewallProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** HTTP adapter for an internally deployed LlamaFirewall scanning service. */
@Component
public class LlamaFirewallClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaFirewallClient.class);
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern INLINE_SECRET =
            Pattern.compile(
                    "(?i)(--?(?:api[-_]?key|token|password|secret)|authorization(?:\\s*[:=])?|bearer\\s+|password\\s*=)\\s*([^\\s,;&]+)");

    private final LlamaFirewallProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final LlamaFirewallCircuitBreaker circuitBreaker;
    private final LlamaFirewallMetrics metrics;
    private final WebClient client;

    @Autowired
    public LlamaFirewallClient(
            LlamaFirewallProperties properties,
            ObjectMapper objectMapper,
            AuditService auditService,
            LlamaFirewallCircuitBreaker circuitBreaker,
            LlamaFirewallMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        HttpClient httpClient =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                Math.max(1, properties.getConnectTimeoutMillis()));
        this.client =
                WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient))
                        .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                        .defaultHeaders(
                                headers -> {
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    if (properties.getApiToken() != null
                                            && !properties.getApiToken().isBlank()) {
                                        headers.setBearerAuth(properties.getApiToken());
                                    }
                                })
                        .build();
    }

    LlamaFirewallClient(
            LlamaFirewallProperties properties,
            ObjectMapper objectMapper,
            AuditService auditService) {
        this(
                properties,
                objectMapper,
                auditService,
                new LlamaFirewallCircuitBreaker(properties),
                LlamaFirewallMetrics.noop());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Mono<ScanDecision> scan(ScanRequest request) {
        if (!properties.isEnabled()) {
            return Mono.just(ScanDecision.allow("LlamaFirewall disabled", 0.0, "disabled"));
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            metrics.recordRequest(request.stage(), "configuration_error", -1);
            return Mono.just(
                    serviceFallback(request, new IllegalStateException("base URL is empty")));
        }
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank()) {
            return Mono.just(ScanDecision.allow("empty content", 0.0, "none"));
        }
        int maxChars = Math.max(1, properties.getMaxContentChars());
        if (content.length() > maxChars) {
            return Mono.just(
                    validationFallback(
                            request,
                            new IllegalArgumentException(
                                    "content exceeds configured scan limit: " + maxChars)));
        }

        LlamaFirewallCircuitBreaker.Permit permit = circuitBreaker.acquirePermit();
        if (permit == LlamaFirewallCircuitBreaker.Permit.REJECTED) {
            metrics.recordRequest(request.stage(), "circuit_open", -1);
            return Mono.just(
                    serviceFallback(request, new IllegalStateException("circuit is open")));
        }

        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        return client.post()
                .uri(normalizePath(properties.getScanPath()))
                .bodyValue(buildRequest(request, requestId))
                .retrieve()
                .bodyToMono(String.class)
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException("LlamaFirewall response body is empty")))
                .timeout(Duration.ofMillis(clampedTimeout(properties.getDecisionTimeoutMillis())))
                .map(this::parseResponse)
                .map(this::parseDecision)
                .doOnNext(
                        decision -> {
                            circuitBreaker.recordSuccess(permit);
                            metrics.recordRequest(
                                    request.stage(),
                                    decision.action().name(),
                                    System.nanoTime() - startedAt);
                            audit(request, requestId, decision);
                        })
                .doOnError(
                        error -> {
                            circuitBreaker.recordFailure(permit);
                            metrics.recordRequest(
                                    request.stage(), "error", System.nanoTime() - startedAt);
                        })
                .onErrorResume(error -> Mono.just(serviceFallback(request, error)));
    }

    public String serializeRedacted(Object value) {
        try {
            return objectMapper.writeValueAsString(redactValue(value));
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to serialize LlamaFirewall input", error);
        }
    }

    private Map<String, Object> buildRequest(ScanRequest request, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("stage", request.stage().wireValue());
        body.put("role", request.role().wireValue());
        body.put("content", request.content());
        body.put("target", request.target());
        body.put("read_only", request.readOnly());
        body.put("metadata", request.metadata());

        RuntimeContext runtimeContext = request.runtimeContext();
        Map<String, Object> context = new LinkedHashMap<>();
        if (runtimeContext != null) {
            context.put("session_id", runtimeContext.getSessionId());
            context.put("user_id", runtimeContext.getUserId());
        }
        TenantContext tenant = TenantContext.from(runtimeContext);
        if (tenant != null) {
            context.put("tenant_id", tenant.orgId());
            context.put("user_id", tenant.userId());
            context.put("role", tenant.role());
        }
        body.put("context", context);
        return body;
    }

    private JsonNode parseResponse(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception error) {
            throw new IllegalStateException("LlamaFirewall response is not valid JSON", error);
        }
    }

    private ScanDecision parseDecision(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("LlamaFirewall response is empty");
        }
        String rawDecision = response.path("decision").asText("").toLowerCase(Locale.ROOT);
        Action action =
                switch (rawDecision) {
                    case "allow" -> Action.ALLOW;
                    case "block" -> Action.BLOCK;
                    case "human_in_the_loop_required", "ask", "review" -> Action.ASK;
                    default ->
                            throw new IllegalStateException(
                                    "Unsupported LlamaFirewall decision: " + rawDecision);
                };
        return new ScanDecision(
                action,
                response.path("reason").asText("LlamaFirewall policy decision"),
                response.path("score").asDouble(0.0),
                response.path("scanner").asText("composite"),
                false);
    }

    private ScanDecision serviceFallback(ScanRequest request, Throwable error) {
        LlamaFirewallProperties.FailureMode mode =
                properties.getFailureMode() == null
                        ? LlamaFirewallProperties.FailureMode.LOCAL_GUARD_ONLY
                        : properties.getFailureMode();
        log.warn(
                "LlamaFirewall scan unavailable stage={} target={} mode={} error={}",
                request.stage(),
                request.target(),
                mode,
                error.getMessage());
        ScanDecision decision =
                switch (mode) {
                    case LOCAL_GUARD_ONLY ->
                            ScanDecision.allow(
                                    "LlamaFirewall unavailable; local security controls active",
                                    0.0,
                                    "local-guard");
                    case DENY -> ScanDecision.block("LlamaFirewall unavailable", 1.0, "fallback");
                    case ALLOW_READ_ONLY ->
                            request.readOnly()
                                    ? ScanDecision.allow(
                                            "LlamaFirewall unavailable; read-only fallback",
                                            0.0,
                                            "fallback")
                                    : ScanDecision.ask(
                                            "LlamaFirewall unavailable; review required",
                                            1.0,
                                            "fallback");
                    case ASK ->
                            ScanDecision.ask(
                                    "LlamaFirewall unavailable; review required", 1.0, "fallback");
                };
        ScanDecision degraded = degraded(decision);
        audit(request, UUID.randomUUID().toString(), degraded);
        return degraded;
    }

    private ScanDecision validationFallback(ScanRequest request, Throwable error) {
        LlamaFirewallProperties.FailureMode mode = properties.getFailureMode();
        log.warn(
                "LlamaFirewall input rejected before scan stage={} target={} mode={} error={}",
                request.stage(),
                request.target(),
                mode,
                error.getMessage());
        ScanDecision decision =
                mode == LlamaFirewallProperties.FailureMode.DENY
                        ? ScanDecision.block(
                                "Content exceeds the configured security scan limit",
                                1.0,
                                "local-validation")
                        : ScanDecision.ask(
                                "Content exceeds the configured security scan limit; review"
                                        + " required",
                                1.0,
                                "local-validation");
        ScanDecision degraded = degraded(decision);
        metrics.recordRequest(request.stage(), "validation_rejected", -1);
        audit(request, UUID.randomUUID().toString(), degraded);
        return degraded;
    }

    private static ScanDecision degraded(ScanDecision decision) {
        return new ScanDecision(
                decision.action(), decision.reason(), decision.score(), decision.scanner(), true);
    }

    private void audit(ScanRequest request, String requestId, ScanDecision decision) {
        if (!properties.isAuditEnabled()
                || decision == null
                || (decision.action() == Action.ALLOW && !decision.degraded())) {
            return;
        }
        TenantContext tenant = TenantContext.from(request.runtimeContext());
        UUID orgId = parseUuid(tenant == null ? null : tenant.orgId());
        UUID actorId =
                parseUuid(
                        tenant == null && request.runtimeContext() != null
                                ? request.runtimeContext().getUserId()
                                : tenant == null ? null : tenant.userId());
        if (orgId == null) {
            return;
        }
        try {
            auditService.record(
                    orgId,
                    actorId,
                    "security." + decision.action().name().toLowerCase(Locale.ROOT),
                    "llama-firewall:" + request.target(),
                    Map.of(
                            "requestId", requestId,
                            "stage", request.stage().wireValue(),
                            "scanner", decision.scanner(),
                            "score", decision.score(),
                            "reason", decision.reason(),
                            "degraded", decision.degraded()));
        } catch (RuntimeException error) {
            log.warn(
                    "Unable to persist LlamaFirewall audit event stage={} target={}",
                    request.stage(),
                    request.target(),
                    error);
        }
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                result.put(key, isSecretKey(key) ? REDACTED : redactValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(LlamaFirewallClient::redactValue).toList();
        }
        if (value instanceof String text) {
            return INLINE_SECRET.matcher(text).replaceAll("$1 " + REDACTED);
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("credential")
                || normalized.contains("authorization");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/v1/scan";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:18082";
        }
        return value.replaceFirst("/+$", "");
    }

    private static int clampedTimeout(int value) {
        return Math.max(1, Math.min(10_000, value));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public enum Stage {
        USER_INPUT("user_input"),
        TOOL_REQUEST("tool_request"),
        TOOL_RESULT("tool_result"),
        ASSISTANT_OUTPUT("assistant_output");

        private final String wireValue;

        Stage(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum Role {
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String wireValue;

        Role(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum Action {
        ALLOW,
        BLOCK,
        ASK
    }

    public record ScanRequest(
            Stage stage,
            Role role,
            String content,
            RuntimeContext runtimeContext,
            String target,
            boolean readOnly,
            Map<String, Object> metadata) {

        public ScanRequest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            target = target == null || target.isBlank() ? "content" : target;
        }
    }

    public record ScanDecision(
            Action action, String reason, double score, String scanner, boolean degraded) {

        public static ScanDecision allow(String reason, double score, String scanner) {
            return new ScanDecision(Action.ALLOW, reason, score, scanner, false);
        }

        public static ScanDecision block(String reason, double score, String scanner) {
            return new ScanDecision(Action.BLOCK, reason, score, scanner, false);
        }

        public static ScanDecision ask(String reason, double score, String scanner) {
            return new ScanDecision(Action.ASK, reason, score, scanner, false);
        }
    }
}
