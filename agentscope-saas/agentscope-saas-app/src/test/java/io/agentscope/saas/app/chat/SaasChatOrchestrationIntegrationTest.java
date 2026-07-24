/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;

import io.agentscope.saas.core.persistence.repo.AssistantRunRepository;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.OrchestrationOutboxRepository;
import io.agentscope.saas.core.persistence.repo.RunEventRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Verifies that a persisted chat produces a durable Run, task, and ordered event stream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class SaasChatOrchestrationIntegrationTest {

    private static final Pattern RUN_ID = Pattern.compile("\\\"runId\\\":\\\"([0-9a-f-]{36})\\\"");

    @LocalServerPort int port;

    @Autowired AssistantRunRepository runRepository;

    @Autowired RunEventRepository eventRepository;

    @Autowired OrchestrationOutboxRepository outboxRepository;

    @Autowired ChatMessageRepository messageRepository;

    private WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient =
                WebTestClient.bindToServer()
                        .responseTimeout(Duration.ofSeconds(30))
                        .baseUrl("http://localhost:" + port)
                        .build();
    }

    @Test
    void persistedChatCreatesQueryableRunTaskAndEvents() {
        Map<?, ?> login =
                webClient
                        .post()
                        .uri("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .bodyValue(
                                Map.of(
                                        "email",
                                        "orchestration-" + System.nanoTime() + "@example.test",
                                        "password",
                                        "orchestration-test-password",
                                        "displayName",
                                        "Orchestration Test"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        String token = String.valueOf(login.get("token"));

        Map<?, ?> createdAgent =
                webClient
                        .post()
                        .uri("/api/agents")
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(APPLICATION_JSON)
                        .bodyValue(Map.of("name", "Orchestration test agent"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        String agentId = String.valueOf(createdAgent.get("id"));
        String requestId = UUID.randomUUID().toString();

        List<String> events =
                webClient
                        .post()
                        .uri("/api/agents/{agentId}/chat/stream", agentId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(APPLICATION_JSON)
                        .accept(TEXT_EVENT_STREAM)
                        .bodyValue(
                                Map.of(
                                        "requestId",
                                        requestId,
                                        "message",
                                        "Complete this durable-run verification."))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(String.class)
                        .getResponseBody()
                        .collectList()
                        .block(Duration.ofSeconds(30));
        String stream = String.join("\n", events);
        Matcher runIdMatcher = RUN_ID.matcher(stream);
        assertThat(runIdMatcher.find()).isTrue();
        String runId = runIdMatcher.group(1);
        assertThat(stream).contains("RUN_STARTED", "RUN_FINISHED");

        String retriedStream =
                String.join(
                        "\n",
                        webClient
                                .post()
                                .uri("/api/agents/{agentId}/chat/stream", agentId)
                                .headers(headers -> headers.setBearerAuth(token))
                                .contentType(APPLICATION_JSON)
                                .accept(TEXT_EVENT_STREAM)
                                .bodyValue(
                                        Map.of(
                                                "requestId",
                                                requestId,
                                                "message",
                                                "Complete this durable-run verification."))
                                .exchange()
                                .expectStatus()
                                .isOk()
                                .returnResult(String.class)
                                .getResponseBody()
                                .collectList()
                                .block(Duration.ofSeconds(30)));
        Matcher retriedRunId = RUN_ID.matcher(retriedStream);
        assertThat(retriedRunId.find()).isTrue();
        assertThat(retriedRunId.group(1)).isEqualTo(runId);
        assertThat(retriedStream).contains("run_reused", "RUN_FINISHED");

        webClient
                .get()
                .uri("/api/agents/{agentId}/runs/{runId}", agentId, runId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.mode")
                .isEqualTo("DIRECT")
                .jsonPath("$.status")
                .isEqualTo("SUCCEEDED");

        webClient
                .get()
                .uri("/api/agents/{agentId}/runs/{runId}/tasks", agentId, runId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].status")
                .isEqualTo("SUCCEEDED");

        webClient
                .get()
                .uri("/api/agents/{agentId}/runs/{runId}/attempts", agentId, runId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].attemptNo")
                .isEqualTo(1)
                .jsonPath("$[0].status")
                .isEqualTo("SUCCEEDED");

        webClient
                .get()
                .uri("/api/agents/{agentId}/runs/{runId}/events", agentId, runId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].eventType")
                .isEqualTo("RUN_CREATED")
                .jsonPath("$[4].eventType")
                .isEqualTo("RUN_SUCCEEDED");

        UUID durableRunId = UUID.fromString(runId);
        var persistedRun = runRepository.findById(durableRunId).orElseThrow();
        assertThat(eventRepository.countByRunId(durableRunId)).isEqualTo(5);
        assertThat(outboxRepository.countByAggregateId(durableRunId)).isEqualTo(5);
        assertThat(messageRepository.countBySessionId(persistedRun.getSessionId())).isEqualTo(2);
    }
}
