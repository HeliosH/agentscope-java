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
package io.agentscope.saas.app.template;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the bundled-template catalog discovered from the classpath by {@link TemplateRegistry} and
 * served read-only by {@link TemplatesController}. {@code /api/templates} is org-agnostic (a global
 * catalog) but still sits behind {@code /api/**} authentication, so each test registers a throwaway
 * user and uses the returned JWT. On the {@code local} H2 profile the registry still scans the
 * bundled {@code templates} resource tree, so the three bundled templates (blank,
 * customer-support, research-assistant) must appear.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class TemplatesControllerTest {

    @LocalServerPort int port;

    private WebTestClient webClient;
    private String token;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String email = "tpl-" + System.nanoTime() + "@example.test";
        // Register returns the auth DTO with a token field; capture it for the authenticated calls.
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
                                        "Tpl"))
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

    private static String extractToken(byte[] json) {
        String s = new String(json, StandardCharsets.UTF_8);
        int i = s.indexOf("\"token\":\"");
        return s.substring(
                i + "\"token\":\"".length(), s.indexOf("\"", i + "\"token\":\"".length()));
    }

    @Test
    void listReturnsAllThreeBundledTemplates() {
        webClient
                .get()
                .uri("/api/templates")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray()
                .jsonPath("$[?(@.id=='blank')]")
                .exists()
                .jsonPath("$[?(@.id=='customer-support')]")
                .exists()
                .jsonPath("$[?(@.id=='research-assistant')]")
                .exists();
    }

    @Test
    void detailReturnsFilesForKnownTemplate() {
        webClient
                .get()
                .uri("/api/templates/customer-support")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("customer-support")
                .jsonPath("$.files")
                .isArray();
    }

    @Test
    void detailReturns404ForUnknownTemplate() {
        webClient
                .get()
                .uri("/api/templates/no-such-template")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
