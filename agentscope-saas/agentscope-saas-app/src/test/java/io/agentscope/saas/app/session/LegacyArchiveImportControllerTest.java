/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.memory.LegacyArchiveImportService;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Owner;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class LegacyArchiveImportControllerTest {
    private final LegacyArchiveImportService service = mock(LegacyArchiveImportService.class);
    private final AuditService audit = mock(AuditService.class);
    private final LegacyArchiveImportController controller =
            new LegacyArchiveImportController(service, audit);
    private final UUID org = UUID.randomUUID(),
            user = UUID.randomUUID(),
            agent = UUID.randomUUID(),
            session = UUID.randomUUID();

    @Test
    void nonAdminCannotCreateReadOrAdvance() {
        Jwt member = jwt("member");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                controller.create(
                                        member,
                                        agent,
                                        session,
                                        new LegacyArchiveImportController.CreateRequest(user, id)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.get(member, agent, session, id, user))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                controller.advance(
                                        member,
                                        agent,
                                        session,
                                        id,
                                        user,
                                        new LegacyArchiveImportController.AdvanceRequest(0L)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.entries(member, agent, session, id, user, 0, 100))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void organizationIsAlwaysBoundToTrustedAdminClaims() {
        UUID version = UUID.randomUUID(), id = UUID.randomUUID();
        var now = OffsetDateTime.now();
        var owner = new Owner(org, user, agent, session);
        Job job =
                new Job(
                        id,
                        org,
                        user,
                        agent,
                        session,
                        0,
                        1,
                        version,
                        "0".repeat(64),
                        0,
                        0,
                        0,
                        0,
                        0,
                        "0".repeat(64),
                        "READY",
                        now,
                        now);
        when(service.create(any(), any())).thenReturn(job);
        assertThat(
                        controller
                                .create(
                                        jwt("admin"),
                                        agent,
                                        session,
                                        new LegacyArchiveImportController.CreateRequest(
                                                user, version))
                                .block(Duration.ofSeconds(5)))
                .isEqualTo(job);
        verify(service).create(owner, version);
    }

    @Test
    void absentOwnerVersionOrCursorAreNotImplicitlyFilledIn() {
        Jwt admin = jwt("admin");
        assertThatThrownBy(
                        () ->
                                controller.create(
                                        admin,
                                        agent,
                                        session,
                                        new LegacyArchiveImportController.CreateRequest(
                                                null, UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                controller.create(
                                        admin,
                                        agent,
                                        session,
                                        new LegacyArchiveImportController.CreateRequest(
                                                user, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                controller.advance(
                                        admin,
                                        agent,
                                        session,
                                        UUID.randomUUID(),
                                        user,
                                        new LegacyArchiveImportController.AdvanceRequest(null)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("org_id", org.toString())
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
