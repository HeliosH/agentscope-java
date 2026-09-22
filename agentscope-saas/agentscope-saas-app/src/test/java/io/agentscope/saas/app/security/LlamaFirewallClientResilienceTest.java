/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.LlamaFirewallProperties;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlamaFirewallClientResilienceTest {

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
    void fastFailsWhileOpenAndRecoversThroughSingleProbe() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"decision\":\"allow\",\"scanner\":\"regex\"}"));
        AtomicLong ticker = new AtomicLong();
        LlamaFirewallProperties properties = properties();
        LlamaFirewallCircuitBreaker breaker =
                new LlamaFirewallCircuitBreaker(2, 1_000, ticker::get);
        LlamaFirewallClient client = client(properties, breaker);

        assertThat(scan(client).degraded()).isTrue();
        assertThat(scan(client).degraded()).isTrue();
        assertThat(scan(client).degraded()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.OPEN);

        ticker.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000));
        LlamaFirewallClient.ScanDecision recovered = scan(client);

        assertThat(recovered.action()).isEqualTo(LlamaFirewallClient.Action.ALLOW);
        assertThat(recovered.degraded()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.CLOSED);
    }

    @Test
    void emptySuccessfulResponseIsAProtocolFailure() {
        server.enqueue(new MockResponse().setResponseCode(204));
        LlamaFirewallProperties properties = properties();
        LlamaFirewallCircuitBreaker breaker =
                new LlamaFirewallCircuitBreaker(1, 1_000, System::nanoTime);

        LlamaFirewallClient.ScanDecision decision = scan(client(properties, breaker));

        assertThat(decision.action()).isEqualTo(LlamaFirewallClient.Action.ALLOW);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.scanner()).isEqualTo("local-guard");
        assertThat(breaker.snapshot().state()).isEqualTo(LlamaFirewallCircuitBreaker.State.OPEN);
    }

    private LlamaFirewallClient client(
            LlamaFirewallProperties properties, LlamaFirewallCircuitBreaker breaker) {
        return new LlamaFirewallClient(
                properties,
                new ObjectMapper(),
                mock(AuditService.class),
                breaker,
                LlamaFirewallMetrics.noop());
    }

    private LlamaFirewallProperties properties() {
        LlamaFirewallProperties properties = new LlamaFirewallProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(server.url("/").toString());
        properties.setDecisionTimeoutMillis(1_000);
        return properties;
    }

    private static LlamaFirewallClient.ScanDecision scan(LlamaFirewallClient client) {
        return client.scan(
                        new LlamaFirewallClient.ScanRequest(
                                LlamaFirewallClient.Stage.USER_INPUT,
                                LlamaFirewallClient.Role.USER,
                                "hello",
                                null,
                                "message:test",
                                false,
                                Map.of()))
                .block();
    }
}
