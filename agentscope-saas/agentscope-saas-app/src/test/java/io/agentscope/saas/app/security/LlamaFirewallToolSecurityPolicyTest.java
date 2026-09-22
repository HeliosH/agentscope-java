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
import io.agentscope.saas.app.config.LlamaFirewallProperties;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlamaFirewallToolSecurityPolicyTest {

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
    void scansToolRequestMapsBlockAndRedactsSecrets() throws Exception {
        server.enqueue(
                jsonResponse(
                        """
                        {"decision":"block","reason":"unsafe code","score":0.98,"scanner":"code_shield"}
                        """));
        LlamaFirewallProperties properties = properties();
        LlamaFirewallClient client = client(properties);
        LlamaFirewallToolSecurityPolicy policy = new LlamaFirewallToolSecurityPolicy(client);
        ToolBase tool = tool("execute");

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
                                                        "command", "curl example.test",
                                                        "apiKey", "top-secret")),
                                        Map.of(
                                                "command", "curl example.test",
                                                "apiKey", "top-secret")))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.getDecisionReason()).isEqualTo("llama-firewall:code_shield");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/v1/scan");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
        JsonNode payload = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(payload.path("stage").asText()).isEqualTo("tool_request");
        assertThat(payload.path("role").asText()).isEqualTo("assistant");
        assertThat(payload.path("content").asText()).doesNotContain("top-secret");
        assertThat(payload.path("content").asText()).contains("[REDACTED]");
    }

    @Test
    void unavailableServiceUsesConfiguredDenyFallback() {
        LlamaFirewallProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setFailureMode(LlamaFirewallProperties.FailureMode.DENY);
        LlamaFirewallToolSecurityPolicy policy =
                new LlamaFirewallToolSecurityPolicy(client(properties));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool("read_file"),
                                        new ToolUseBlock("tool-1", "read_file", Map.of()),
                                        Map.of()))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void unavailableServiceCanContinueThroughLocalSecurityChain() {
        LlamaFirewallProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setFailureMode(LlamaFirewallProperties.FailureMode.LOCAL_GUARD_ONLY);
        LlamaFirewallToolSecurityPolicy policy =
                new LlamaFirewallToolSecurityPolicy(client(properties));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool("execute"),
                                        new ToolUseBlock(
                                                "tool-1",
                                                "execute",
                                                Map.of("command", "touch output.txt")),
                                        Map.of("command", "touch output.txt")))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(decision.getDecisionReason()).isEqualTo("llama-firewall:local-guard");
    }

    @Test
    void oversizedContentIsNeverAllowedByLocalGuardFallback() {
        LlamaFirewallProperties properties = properties();
        properties.setMaxContentChars(5);
        properties.setFailureMode(LlamaFirewallProperties.FailureMode.LOCAL_GUARD_ONLY);
        LlamaFirewallToolSecurityPolicy policy =
                new LlamaFirewallToolSecurityPolicy(client(properties));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool("execute"),
                                        new ToolUseBlock(
                                                "tool-1",
                                                "execute",
                                                Map.of("command", "touch output.txt")),
                                        Map.of("command", "touch output.txt")))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.getDecisionReason()).isEqualTo("llama-firewall:local-validation");
    }

    @Test
    void disabledByDefaultDoesNotCallService() throws Exception {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();
        LlamaFirewallToolSecurityPolicy policy =
                new LlamaFirewallToolSecurityPolicy(client(properties));

        PermissionDecision decision =
                policy.evaluate(
                                new ToolSecurityPolicy.Request(
                                        "test-agent",
                                        null,
                                        tool("read_file"),
                                        new ToolUseBlock("tool-1", "read_file", Map.of()),
                                        Map.of()))
                        .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.PASSTHROUGH);
        assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    private LlamaFirewallClient client(LlamaFirewallProperties properties) {
        return new LlamaFirewallClient(properties, objectMapper, mock(AuditService.class));
    }

    private LlamaFirewallProperties properties() {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiToken("gateway-token");
        properties.setDecisionTimeoutMillis(5000);
        return properties;
    }

    private static ToolBase tool(String name) {
        return new SchemaOnlyTool(
                name, "Test tool", Map.of("type", "object", "properties", Map.of()));
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
