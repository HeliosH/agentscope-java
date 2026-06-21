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
package io.agentscope.saas.app.marketplace;

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
 * Verifies the admin-role gating on {@link MarketplacesController} write endpoints. The write
 * endpoints (create / update / delete / connection-test) manage org-level marketplace config, which
 * carries connection credentials, so they are admin-only — mirroring {@code
 * OrgToolsConfigController} for MCP. Read endpoints (list / browse skills) stay open to every org
 * member so members can still browse and install skills.
 *
 * <p>Self-register hard-codes {@code role=member} (there is no API path to mint an admin token —
 * see the C2 notes), so these tests exercise the member-denied path (the core security constraint)
 * and the read-allowed path. The admin-allowed path is left to SQL-seeded e2e.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MarketplacesAdminControllerTest {

    @LocalServerPort int port;

    private WebTestClient webClient;
    private String token;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String email = "mkt-" + System.nanoTime() + "@example.test";
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
                                        "Mkt"))
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

    /** A minimal valid git marketplace write payload (valid shape; admin gating fires first). */
    private static Map<String, Object> gitMarketplace(String id) {
        return Map.of(
                "id",
                id,
                "type",
                "git",
                "properties",
                Map.of("remoteUrl", "https://example.test/repo.git"));
    }

    @Test
    void memberRoleCannotCreateMarketplace() {
        webClient
                .post()
                .uri("/api/marketplaces")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(gitMarketplace("m" + System.nanoTime()))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void memberRoleCannotUpdateMarketplace() {
        // requireAdmin fires before the 404 lookup, so a non-existent id is still 403 — proving the
        // gate applies regardless of whether the resource exists.
        webClient
                .put()
                .uri("/api/marketplaces/no-such-marketplace")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(gitMarketplace("no-such-marketplace"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void memberRoleCannotDeleteMarketplace() {
        webClient
                .delete()
                .uri("/api/marketplaces/no-such-marketplace")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void memberRoleCannotTestTransientMarketplace() {
        webClient
                .post()
                .uri("/api/marketplaces/test")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue(gitMarketplace("probe-" + System.nanoTime()))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void memberRoleCannotTestExistingMarketplace() {
        webClient
                .post()
                .uri("/api/marketplaces/no-such-marketplace/test")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void memberCanListMarketplaces() {
        // Read path stays open to members — the gating must not over-reach into browsing.
        webClient
                .get()
                .uri("/api/marketplaces")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray();
    }
}
