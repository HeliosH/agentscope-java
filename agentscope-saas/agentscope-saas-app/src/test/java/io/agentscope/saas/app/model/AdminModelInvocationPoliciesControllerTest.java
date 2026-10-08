/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class AdminModelInvocationPoliciesControllerTest {
    private final UUID orgId = UUID.randomUUID(), actorId = UUID.randomUUID();
    private final ModelInvocationPolicyService service = mock(ModelInvocationPolicyService.class);
    private final AdminModelInvocationPoliciesController controller =
            new AdminModelInvocationPoliciesController(service);

    @Test
    void unauthenticatedRequestsCannotReadOrUpdatePolicies() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThatThrownBy(() -> controller.update(null, "VERIFY", null))
                .hasMessageContaining("401");
        verifyNoInteractions(service);
    }

    @Test
    void ordinaryMembersCannotReadOrUpdatePolicies() {
        assertThatThrownBy(() -> controller.list(jwt("member"))).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.update(jwt("member"), "VERIFY", null))
                .hasMessageContaining("403");
        verifyNoInteractions(service);
    }

    @Test
    void organizationIsAlwaysTakenFromAuthenticatedClaims() {
        when(service.list(orgId)).thenReturn(List.of());
        controller.list(jwt("admin")).block();
        verify(service).list(orgId);
    }

    @Test
    void updateUsesTrustedOrganizationAndActor() {
        var command =
                new ModelInvocationPolicyService.PolicyCommand(null, 8000, 1024, 30, "defaults-v1");
        controller.update(jwt("admin"), "VERIFY", command).block();
        verify(service).update(orgId, actorId, "VERIFY", command);
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("test")
                .header("alg", "HS256")
                .subject(actorId.toString())
                .claim("org_id", orgId.toString())
                .claim("role", role)
                .build();
    }
}
