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
package io.agentscope.saas.app.auth;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end auth on the {@code local} H2 profile: exercises the Phase F4 bootstrap bypass path —
 * {@link AuthBootstrapRepository} over the admin (RLS-bypass) DataSource. Register then login must
 * both succeed and return a signed JWT, proving the pre-tenant-context queries (find user by email,
 * find org by slug, insert user) route through the admin DataSource rather than the RLS-wrapped
 * primary (which would deny all rows with no {@code app.current_org} set). On H2 there is no RLS, so
 * this validates wiring + the JdbcTemplate/RowMapper path; the DB-enforced isolation is verified
 * separately on PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AuthBootstrapIntegrationTest {

    @LocalServerPort int port;

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
    void registerThenLoginReturnsJwt() {
        String email = "f4rls-" + System.nanoTime() + "@example.test";
        webClient
                .post()
                .uri("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "s3cret-pw", "displayName", "F4"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .isNotEmpty()
                .jsonPath("$.email")
                .isEqualTo(email)
                .jsonPath("$.orgId")
                .isNotEmpty();

        webClient
                .post()
                .uri("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "s3cret-pw"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .isNotEmpty()
                .jsonPath("$.email")
                .isEqualTo(email);
    }

    @Test
    void loginWithUnknownCredentialsIsUnauthorized() {
        webClient
                .post()
                .uri("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "email",
                                "no-such-user-" + System.nanoTime() + "@example.test",
                                "password",
                                "x"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
