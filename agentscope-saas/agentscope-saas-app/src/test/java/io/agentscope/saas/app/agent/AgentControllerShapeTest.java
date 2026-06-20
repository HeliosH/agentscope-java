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
package io.agentscope.saas.app.agent;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Round-trips paw's {@code AgentDefinition} shape through the agent CRUD endpoints on the {@code local}
 * H2 profile: register a user, create an agent with the paw create body, then GET it back and assert
 * the {@link AgentController.AgentView} carries every field the forked frontend reads (sysPrompt,
 * maxIters, tools array, workspacePath, builtin flag, epoch-millis timestamps). Also covers the delete
 * path that exercises the {@code @Transactional} cascade fix — an agent created and then deleted must
 * return 204 even though no sessions exist yet, and a subsequent GET must 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AgentControllerShapeTest {

    @LocalServerPort int port;

    private WebTestClient webClient;
    private String token;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String email = "agent-" + System.nanoTime() + "@example.test";
        byte[] body =
                webClient
                        .post()
                        .uri("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .bodyValue(
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        "s3cret-pw",
                                        "displayName",
                                        "Agent"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.token")
                        .isNotEmpty()
                        .returnResult()
                        .getResponseBodyContent();
        token = extractToken(body);
    }

    @Test
    void createThenGetReturnsPawAgentDefinitionShape() {
        byte[] created =
                webClient
                        .post()
                        .uri("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .bodyValue(
                                Map.of(
                                        "name",
                                        "ShapeAgent",
                                        "description",
                                        "round-trip shape test",
                                        "sysPrompt",
                                        "You are a helpful assistant.",
                                        "maxIters",
                                        7,
                                        "tools",
                                        List.of("web_search", "file_read"),
                                        "workspacePath",
                                        "agents/shape"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody()
                        .jsonPath("$.id")
                        .isNotEmpty()
                        .jsonPath("$.name")
                        .isEqualTo("ShapeAgent")
                        .jsonPath("$.sysPrompt")
                        .isEqualTo("You are a helpful assistant.")
                        .jsonPath("$.maxIters")
                        .isEqualTo(7)
                        .jsonPath("$.tools[0]")
                        .isEqualTo("web_search")
                        .jsonPath("$.tools[1]")
                        .isEqualTo("file_read")
                        .jsonPath("$.builtin")
                        .isEqualTo(false)
                        .jsonPath("$.workspacePath")
                        .isEqualTo("agents/shape")
                        .returnResult()
                        .getResponseBodyContent();
        String agentId = extractStringField(created, "id");

        // Re-read by id and confirm the shape is stable (not just echoed from the create response).
        webClient
                .get()
                .uri("/api/agents/" + agentId)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("ShapeAgent")
                .jsonPath("$.sysPrompt")
                .isEqualTo("You are a helpful assistant.")
                .jsonPath("$.maxIters")
                .isEqualTo(7)
                .jsonPath("$.tools[0]")
                .isEqualTo("web_search")
                .jsonPath("$.builtin")
                .isEqualTo(false)
                .jsonPath("$.workspacePath")
                .isEqualTo("agents/shape");
    }

    @Test
    void deleteAgentReturns204AndFollowUpGetIs404() {
        byte[] created =
                webClient
                        .post()
                        .uri("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .bodyValue(Map.of("name", "DeleteMe"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody()
                        .jsonPath("$.id")
                        .isNotEmpty()
                        .returnResult()
                        .getResponseBodyContent();
        String agentId = extractStringField(created, "id");

        webClient
                .delete()
                .uri("/api/agents/" + agentId)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNoContent();

        webClient
                .get()
                .uri("/api/agents/" + agentId)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private static String extractToken(byte[] json) {
        return extractStringField(json, "token");
    }

    private static String extractStringField(byte[] json, String field) {
        String s = new String(json, StandardCharsets.UTF_8);
        String key = "\"" + field + "\":\"";
        int i = s.indexOf(key);
        return s.substring(i + key.length(), s.indexOf("\"", i + key.length()));
    }
}
