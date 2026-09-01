/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.ToolSecurityPolicy;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.ClawSentryProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * AgentScope adapter for ClawSentry's AHP synchronous decision endpoint.
 *
 * <p>This is a deployment-time integration. The gateway is not a replacement for local permission
 * checks: the core runtime evaluates its local policy first and merges this policy before tool
 * execution. A gateway response can tighten or rewrite a request, never weaken a local deny.
 */
@Component
public class ClawSentryToolSecurityPolicy implements ToolSecurityPolicy {

    private static final Logger log = LoggerFactory.getLogger(ClawSentryToolSecurityPolicy.class);
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern INLINE_SECRET =
            Pattern.compile(
                    "(?i)(--?(?:api[-_]?key|token|password|secret)|authorization(?:\\s*[:=])?|bearer\\s+|password\\s*=)\\s*([^\\s,;&]+)");

    private final ClawSentryProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final WebClient client;

    public ClawSentryToolSecurityPolicy(
            ClawSentryProperties properties, ObjectMapper objectMapper, AuditService auditService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
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

    @Override
    public Mono<PermissionDecision> evaluate(Request request) {
        if (!properties.isEnabled()) {
            return Mono.just(PermissionDecision.passthrough("clawsentry disabled"));
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            return Mono.just(fallback(request, new IllegalStateException("base URL is empty")));
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = buildRequest(request, requestId);
        return client.post()
                .uri(normalizePath(properties.getApiPath()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(clampedTimeout(properties.getDecisionTimeoutMillis())))
                .map(this::parseResponse)
                .map(this::parseDecision)
                .doOnNext(decision -> audit(request, requestId, decision))
                .onErrorResume(error -> Mono.just(fallback(request, error)));
    }

    private JsonNode parseResponse(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception error) {
            throw new IllegalStateException("ClawSentry response is not valid JSON", error);
        }
    }

    private Map<String, Object> buildRequest(Request request, String requestId) {
        RuntimeContext runtimeContext = request.runtimeContext();
        String sessionId =
                valueOr(
                        runtimeContext == null ? null : runtimeContext.getSessionId(),
                        "unknown-session");
        String agentId = valueOr(request.agentName(), "agentscope-agent");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "ahp.1.0");
        event.put("event_id", UUID.randomUUID().toString());
        event.put("trace_id", sessionId);
        event.put("event_type", "pre_action");
        event.put("session_id", sessionId);
        event.put("agent_id", agentId);
        event.put("source_framework", valueOr(properties.getSourceFramework(), "agentscope-java"));
        event.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        event.put("event_subtype", "PreToolUse");
        event.put("tool_name", request.tool().getName());
        event.put("risk_hints", riskHints(request.tool(), request.input()));
        event.put("payload", redactedPayload(request.tool().getName(), request.input()));
        event.put("source_protocol_version", "1.0");
        event.put("mapping_profile", "agentscope-java@ahp.1.0/profile.v1");

        Map<String, Object> context = new LinkedHashMap<>();
        TenantContext tenant = TenantContext.from(request.runtimeContext());
        if (tenant != null) {
            context.put("tenant_id", tenant.orgId());
            context.put("user_id", tenant.userId());
            context.put("role", tenant.role());
        }
        context.put("caller_adapter", "agentscope-java.v1");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("rpc_version", "sync_decision.1.0");
        params.put("request_id", requestId);
        params.put("deadline_ms", clampedTimeout(properties.getDecisionTimeoutMillis()));
        params.put("decision_tier", valueOr(properties.getDecisionTier(), "L1"));
        params.put("event", event);
        params.put("context", context);

        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", requestId);
        rpc.put("method", "ahp/sync_decision");
        rpc.put("params", params);
        return rpc;
    }

    private PermissionDecision parseDecision(JsonNode response) {
        JsonNode decision = response == null ? null : response.path("result").path("decision");
        if (decision == null || decision.isMissingNode() || !decision.isObject()) {
            throw new IllegalStateException("ClawSentry response does not contain result.decision");
        }
        String action = decision.path("decision").asText("").toLowerCase(Locale.ROOT);
        String reason = decision.path("reason").asText("ClawSentry policy decision");
        String policyId = decision.path("policy_id").asText("");
        String message = policyId.isBlank() ? reason : reason + " (" + policyId + ")";
        return switch (action) {
            case "allow" ->
                    PermissionDecision.allow(message).withDecisionReason("clawsentry:" + policyId);
            case "block" ->
                    PermissionDecision.deny(message).withDecisionReason("clawsentry:" + policyId);
            case "defer" ->
                    PermissionDecision.ask(message).withDecisionReason("clawsentry:" + policyId);
            case "modify" -> parseModification(decision, message, policyId);
            default ->
                    throw new IllegalStateException("Unsupported ClawSentry decision: " + action);
        };
    }

    private PermissionDecision parseModification(
            JsonNode decision, String message, String policyId) {
        JsonNode modified = decision.path("modified_payload");
        if (!modified.isObject()) {
            return PermissionDecision.ask(message + " (modified payload missing)")
                    .withDecisionReason("clawsentry:" + policyId);
        }
        Map<String, Object> input = objectMapper.convertValue(modified, Map.class);
        return PermissionDecision.allow(message)
                .withDecisionReason("clawsentry:" + policyId)
                .withUpdatedInput(input);
    }

    private PermissionDecision fallback(Request request, Throwable error) {
        String toolName = request.tool().getName();
        ClawSentryProperties.FailureMode failureMode =
                properties.getFailureMode() == null
                        ? ClawSentryProperties.FailureMode.ASK
                        : properties.getFailureMode();
        log.warn(
                "ClawSentry decision unavailable for tool={} mode={} error={}",
                toolName,
                failureMode,
                error.getMessage());
        return switch (failureMode) {
            case DENY -> PermissionDecision.deny("ClawSentry unavailable; execution denied");
            case ALLOW_READ_ONLY ->
                    request.tool().isReadOnly()
                            ? PermissionDecision.passthrough(
                                    "ClawSentry unavailable; read-only fallback")
                            : PermissionDecision.ask(
                                    "ClawSentry unavailable; confirmation required");
            case ASK -> PermissionDecision.ask("ClawSentry unavailable; confirmation required");
        };
    }

    private void audit(Request request, String requestId, PermissionDecision decision) {
        if (!properties.isAuditEnabled()
                || decision == null
                || decision.getBehavior() == PermissionBehavior.ALLOW
                || decision.getBehavior() == PermissionBehavior.PASSTHROUGH) {
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
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("behavior", decision.getBehavior().name());
        detail.put("reason", decision.getMessage());
        detail.put("decisionReason", decision.getDecisionReason());
        detail.put("source", "clawsentry");
        auditService.record(
                orgId,
                actorId,
                "security." + decision.getBehavior().name().toLowerCase(Locale.ROOT),
                "tool:" + request.tool().getName(),
                detail);
    }

    private static Map<String, Object> redactedPayload(String toolName, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("arguments", redactValue(input));
        return payload;
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
            return collection.stream().map(ClawSentryToolSecurityPolicy::redactValue).toList();
        }
        if (value instanceof String text) {
            return INLINE_SECRET.matcher(text).replaceAll("$1 " + REDACTED);
        }
        return value;
    }

    private static List<String> riskHints(ToolBase tool, Map<String, Object> input) {
        List<String> hints = new ArrayList<>();
        String name = tool.getName().toLowerCase(Locale.ROOT);
        if (name.contains("shell")
                || name.contains("bash")
                || name.contains("exec")
                || name.contains("command")) {
            hints.add("shell_execution");
        }
        if (!tool.isReadOnly()) {
            hints.add("state_change");
        }
        if (tool.isExternalTool() || tool.isMcp()) {
            hints.add("external_tool");
        }
        if (containsSecretKey(input)) {
            hints.add("sensitive_argument");
        }
        return hints;
    }

    private static boolean containsSecretKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isSecretKey(String.valueOf(entry.getKey()))
                        || containsSecretKey(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(ClawSentryToolSecurityPolicy::containsSecretKey);
        }
        return false;
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
            return "/ahp";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }
        return value.replaceFirst("/+$", "");
    }

    private static int clampedTimeout(int value) {
        return Math.max(1, Math.min(5000, value));
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
}
