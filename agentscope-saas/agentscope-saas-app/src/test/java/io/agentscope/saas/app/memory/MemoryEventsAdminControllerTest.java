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
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import io.agentscope.saas.core.persistence.repo.MemoryEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class MemoryEventsAdminControllerTest {

    private final MemoryEventRepository repository = mock(MemoryEventRepository.class);
    private final MemoryEventsAdminController controller =
            new MemoryEventsAdminController(repository);

    @Test
    void adminCanQueryOwnOrgMemoryEventsWithFilters() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MemoryEventEntity event = event(orgId, userId);
        when(repository.findAdminEvents(
                        eq(orgId), eq(userId), eq("session-1"), eq("failed"), any(Pageable.class)))
                .thenReturn(List.of(event));

        var response =
                controller
                        .list(adminJwt(orgId), userId.toString(), "session-1", "failed", 10)
                        .block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        var view = response.getBody().get(0);
        assertThat(view.id()).isEqualTo(event.getId().toString());
        assertThat(view.orgId()).isEqualTo(orgId.toString());
        assertThat(view.userId()).isEqualTo(userId.toString());
        assertThat(view.syncStatus()).isEqualTo("failed");
        assertThat(view.lastError()).isEqualTo("projection down");
        assertThat(view.contentJson()).contains("remember tea");
        verify(repository)
                .findAdminEvents(
                        eq(orgId), eq(userId), eq("session-1"), eq("failed"), any(Pageable.class));
    }

    @Test
    void memberCannotQueryMemoryEvents() {
        UUID orgId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.list(memberJwt(orgId), null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidUserIdFilterIsRejected() {
        UUID orgId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.list(adminJwt(orgId), "not-a-uuid", null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static MemoryEventEntity event(UUID orgId, UUID userId) {
        MemoryEventEntity event = new MemoryEventEntity();
        event.setId(UUID.randomUUID());
        event.setOrgId(orgId);
        event.setUserId(userId);
        event.setAgentId("assistant");
        event.setSessionId("session-1");
        event.setSource("mem0");
        event.setEventType("conversation");
        event.setContentJson("{\"messages\":[{\"role\":\"user\",\"content\":\"remember tea\"}]}");
        event.setMetadataJson("{\"org_id\":\"" + orgId + "\"}");
        event.setSyncStatus("failed");
        event.setSyncAttempts(2);
        event.setLastError("projection down");
        event.setSyncedAt(OffsetDateTime.now());
        event.setUpdatedAt(OffsetDateTime.now());
        return event;
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
