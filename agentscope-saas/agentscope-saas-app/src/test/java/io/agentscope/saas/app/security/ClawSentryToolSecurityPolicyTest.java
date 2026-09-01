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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.ToolSecurityPolicy;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.ClawSentryProperties;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClawSentryToolSecurityPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsAhpRequestParsesModifyAndRedactsSecrets() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {
                                  "jsonrpc": "2.0",
                                  "id": "request-1",
                                  "result": {
                                    "decision": {
                                      "decision": "modify",
                                      "reason": "command normalized",
                                      "policy_id": "test-policy",
                                      "modified_payload": {"command": "echo sanitized"}
                                    }
                                  }
                                }
                                """));

        ClawSentryProperties properties = properties();
        ClawSentryToolSecurityPolicy policy =
                new ClawSentryToolSecurityPolicy(
                        properties, objectMapper, mock(AuditService.class));
        ToolBase tool =
                new SchemaOnlyTool(
                        "execute",
                        "Execute a command",
                        Map.of("type", "object", "properties", Map.of()));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool,
                                        new ToolUseBlock(
                                                "tool-1",
                                                "execute",
                                                Map.of(
                                                        "command",
                                                        "echo secret",
                                                        "apiKey",
                                                        "top-secret")),
                                        Map.of("command", "echo secret", "apiKey", "top-secret")))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.getUpdatedInput()).containsEntry("command", "echo sanitized");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/ahp");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
        JsonNode payload = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(payload.path("method").asText()).isEqualTo("ahp/sync_decision");
        assertThat(payload.at("/params/event/event_subtype").asText()).isEqualTo("PreToolUse");
        assertThat(payload.toString()).doesNotContain("top-secret");
        assertThat(payload.at("/params/event/payload/arguments/apiKey").asText())
                .isEqualTo("[REDACTED]");
    }

    @Test
    void unavailableGatewayUsesConfiguredDenyFallback() {
        ClawSentryProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setFailureMode(ClawSentryProperties.FailureMode.DENY);
        ClawSentryToolSecurityPolicy policy =
                new ClawSentryToolSecurityPolicy(
                        properties, objectMapper, mock(AuditService.class));
        ToolBase tool =
                new SchemaOnlyTool(
                        "read_file",
                        "Read a file",
                        Map.of("type", "object", "properties", Map.of()));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool,
                                        new ToolUseBlock("tool-1", "read_file", Map.of()),
                                        Map.of()))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void disabledByDefaultDoesNotCallGateway() throws Exception {
        ClawSentryProperties properties = new ClawSentryProperties();
        ClawSentryToolSecurityPolicy policy =
                new ClawSentryToolSecurityPolicy(
                        properties, objectMapper, mock(AuditService.class));
        ToolBase tool =
                new SchemaOnlyTool(
                        "read_file",
                        "Read a file",
                        Map.of("type", "object", "properties", Map.of()));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool,
                                        new ToolUseBlock("tool-1", "read_file", Map.of()),
                                        Map.of()))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    private ClawSentryProperties properties() {
        ClawSentryProperties properties = new ClawSentryProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiPath("/ahp");
        properties.setApiToken("gateway-token");
        properties.setDecisionTimeoutMillis(5000);
        return properties;
    }
}
