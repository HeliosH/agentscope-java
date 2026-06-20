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
package io.agentscope.saas.app.workspace;

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
 * Exercises the subagent endpoints against the real per-user workspace filesystem on the {@code local}
 * profile (no stubbing — {@code WorkspaceResolver} routes through the harness {@code HarnessAgent}
 * filesystem, which is the path the forked frontend actually hits). Covers the full lifecycle: PUT
 * upsert → GET list → DELETE (204) → DELETE again (404), plus the validation rules (blank description
 * → 400, path-traversal name → 400). Subagents are {@code subagents/<name>.md} files, not a DB
 * entity, so the assertions read back the written file via the list/detail round-trip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class SubagentControllerTest {

    @LocalServerPort int port;

    private WebTestClient webClient;
    private String token;
    private String agentId;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String email = "sub-" + System.nanoTime() + "@example.test";
        byte[] authBody =
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
                                        "Sub"))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.token")
                        .isNotEmpty()
                        .returnResult()
                        .getResponseBodyContent();
        token = extractField(authBody, "token");

        byte[] created =
                webClient
                        .post()
                        .uri("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .bodyValue(Map.of("name", "SubHostAgent"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody()
                        .jsonPath("$.id")
                        .isNotEmpty()
                        .returnResult()
                        .getResponseBodyContent();
        agentId = extractField(created, "id");
    }

    @Test
    void upsertListDeleteRoundTrip() {
        // PUT a subagent with a description (the only required field).
        webClient
                .put()
                .uri("/api/agents/" + agentId + "/workspace/subagents/researcher")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "description",
                                "Does web research",
                                "model",
                                "stub",
                                "maxIters",
                                3,
                                "tools",
                                List.of("web_search")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("researcher")
                .jsonPath("$.description")
                .isEqualTo("Does web research")
                .jsonPath("$.model")
                .isEqualTo("stub")
                .jsonPath("$.maxIters")
                .isEqualTo(3)
                .jsonPath("$.tools[0]")
                .isEqualTo("web_search");

        // GET list reflects the written file.
        webClient
                .get()
                .uri("/api/agents/" + agentId + "/workspace/subagents")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.name=='researcher')]")
                .exists();

        // DELETE → 204, then a second DELETE → 404 (file gone).
        webClient
                .delete()
                .uri("/api/agents/" + agentId + "/workspace/subagents/researcher")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNoContent();
        webClient
                .delete()
                .uri("/api/agents/" + agentId + "/workspace/subagents/researcher")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void upsertWithBlankDescriptionIs400() {
        webClient
                .put()
                .uri("/api/agents/" + agentId + "/workspace/subagents/noDesc")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("description", "   "))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void upsertWithTraversalStyleNameIs400() {
        // A bare ".." (no slash, so the path still matches the route var) hits validateName and is
        // rejected as a bad request by the controller.
        webClient
                .put()
                .uri("/api/agents/" + agentId + "/workspace/subagents/..")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("description", "bad name"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private static String extractField(byte[] json, String field) {
        String s = new String(json, StandardCharsets.UTF_8);
        String key = "\"" + field + "\":\"";
        int i = s.indexOf(key);
        return s.substring(i + key.length(), s.indexOf("\"", i + key.length()));
    }
}
