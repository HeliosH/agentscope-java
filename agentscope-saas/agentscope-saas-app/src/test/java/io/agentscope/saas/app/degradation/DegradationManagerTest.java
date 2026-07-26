/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.degradation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.observability.AgentRunMetrics;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DegradationManagerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probesTheSingleConfiguredRemoteProviderWithProviderAuthentication() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/health",
                exchange -> {
                    apiKey.set(exchange.getRequestHeaders().getFirst("OPEN-SANDBOX-API-KEY"));
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();

        SaasProperties properties = properties("opensandbox");
        properties
                .getSandbox()
                .setOpenSandboxApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getSandbox().setOpenSandboxApiKey("test-key");
        properties.getDegradation().setSandboxProviderHealthPath("/health");

        var status =
                manager(properties).currentStatus(true).dependencies().stream()
                        .filter(row -> row.component().equals("sandbox_provider"))
                        .findFirst()
                        .orElseThrow();

        assertThat(status.status()).isEqualTo("healthy");
        assertThat(status.blocksChat()).isFalse();
        assertThat(apiKey).hasValue("test-key");
    }

    @Test
    void marksRemoteProviderDegradedWhenItsHealthEndpointFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/health",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();

        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getDegradation().setSandboxProviderHealthPath("/health");

        var status =
                manager(properties).currentStatus(true).dependencies().stream()
                        .filter(row -> row.component().equals("sandbox_provider"))
                        .findFirst()
                        .orElseThrow();

        assertThat(status.status()).isEqualTo("degraded");
        assertThat(status.blocksChat()).isTrue();
    }

    private static SaasProperties properties(String provider) {
        SaasProperties properties = new SaasProperties();
        properties.getRedis().setEnabled(false);
        properties.getFileStore().setEnabled(false);
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setType(provider);
        properties.getSandbox().getSnapshot().setEnabled(false);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static DegradationManager manager(SaasProperties properties) {
        return new DegradationManager(
                properties,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(AgentRunMetrics.class));
    }
}
