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
package io.agentscope.saas.app.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class SandboxAdminControllerTest {

    private final SandboxRepository repository = mock(SandboxRepository.class);
    private final SandboxAdminController controller = new SandboxAdminController(repository);

    @Test
    void adminCanQueryOwnOrgSandboxesWithFilters() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SandboxEntity sandbox = sandbox(orgId, userId, "active", "e2b", -30);
        when(repository.findAdminSandboxes(
                        eq(orgId),
                        eq(userId),
                        eq("active"),
                        eq("e2b"),
                        eq(true),
                        any(OffsetDateTime.class),
                        any(Pageable.class)))
                .thenReturn(List.of(sandbox));

        var response =
                controller
                        .list(adminJwt(orgId), userId.toString(), "active", "e2b", true, 10)
                        .block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        var view = response.getBody().get(0);
        assertThat(view.id()).isEqualTo(sandbox.getId().toString());
        assertThat(view.orgId()).isEqualTo(orgId.toString());
        assertThat(view.userId()).isEqualTo(userId.toString());
        assertThat(view.sandboxType()).isEqualTo("e2b");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.expired()).isTrue();
        verify(repository)
                .findAdminSandboxes(
                        eq(orgId),
                        eq(userId),
                        eq("active"),
                        eq("e2b"),
                        eq(true),
                        any(OffsetDateTime.class),
                        any(Pageable.class));
    }

    @Test
    void memberCannotQuerySandboxes() {
        UUID orgId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.list(memberJwt(orgId), null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidUserIdFilterIsRejected() {
        UUID orgId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                controller.list(
                                        adminJwt(orgId), "not-a-uuid", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static SandboxEntity sandbox(
            UUID orgId, UUID userId, String status, String sandboxType, long expiresInSeconds) {
        SandboxEntity sandbox = new SandboxEntity();
        sandbox.setId(UUID.randomUUID());
        sandbox.setOrgId(orgId);
        sandbox.setUserId(userId);
        sandbox.setAgentId(UUID.randomUUID());
        sandbox.setSessionId("session-1");
        sandbox.setSandboxType(sandboxType);
        sandbox.setExternalId("external-1");
        sandbox.setStatus(status);
        sandbox.setLastUsedAt(OffsetDateTime.now());
        sandbox.setExpiresAt(OffsetDateTime.now().plusSeconds(expiresInSeconds));
        return sandbox;
    }

    private static Jwt adminJwt(UUID orgId) {
        return jwt(orgId, "admin");
    }

    private static Jwt memberJwt(UUID orgId) {
        return jwt(orgId, "member");
    }

    private static Jwt jwt(UUID orgId, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("org_id", orgId.toString())
                .claim("role", role)
                .build();
    }
}
