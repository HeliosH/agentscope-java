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
package io.agentscope.saas.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.UserEntity;
import io.agentscope.saas.core.persistence.repo.TierPolicyRepository;
import io.agentscope.saas.core.persistence.repo.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class AdminUsersControllerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TierPolicyRepository tierPolicies = mock(TierPolicyRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminUsersController controller =
            new AdminUsersController(users, tierPolicies, audit);

    @Test
    void memberCannotListUsers() {
        assertThatThrownBy(
                        () ->
                                controller.list(
                                        jwt(UUID.randomUUID(), UUID.randomUUID(), "member"), 10))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanUpdateUserTierAndRoleWithAudit() {
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserEntity user = user(orgId, userId, "member", "standard");
        when(users.findByOrgIdAndId(orgId, userId)).thenReturn(Optional.of(user));
        when(tierPolicies.existsById("advanced")).thenReturn(true);
        when(users.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response =
                controller
                        .update(
                                jwt(orgId, actorId, "admin"),
                                userId.toString(),
                                new AdminUsersController.UpdateUserRequest(
                                        "Team Lead", "admin", "advanced"))
                        .block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().role()).isEqualTo("admin");
        assertThat(response.getBody().tier()).isEqualTo("advanced");
        verify(audit)
                .record(
                        eq(orgId),
                        eq(actorId),
                        eq("admin.user.update"),
                        eq("user:" + userId),
                        any());
    }

    @Test
    void cannotDemoteLastAdmin() {
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserEntity user = user(orgId, userId, "admin", "standard");
        when(users.findByOrgIdAndId(orgId, userId)).thenReturn(Optional.of(user));
        when(users.countByOrgIdAndRole(orgId, "admin")).thenReturn(1L);

        assertThatThrownBy(
                        () ->
                                controller
                                        .update(
                                                jwt(orgId, actorId, "admin"),
                                                userId.toString(),
                                                new AdminUsersController.UpdateUserRequest(
                                                        null, "member", null))
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void orgAdminCannotUpdatePlatformAdminUser() {
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserEntity user = user(orgId, userId, "platform_admin", "standard");
        when(users.findByOrgIdAndId(orgId, userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () ->
                                controller
                                        .update(
                                                jwt(orgId, actorId, "admin"),
                                                userId.toString(),
                                                new AdminUsersController.UpdateUserRequest(
                                                        "Changed", null, null))
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static UserEntity user(UUID orgId, UUID userId, String role, String tier) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrgId(orgId);
        user.setEmail("user-" + userId + "@example.test");
        user.setDisplayName("User");
        user.setRole(role);
        user.setTier(tier);
        return user;
    }

    private static Jwt jwt(UUID orgId, UUID subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject.toString())
                .claim("org_id", orgId.toString())
                .claim("role", role)
                .build();
    }
}
