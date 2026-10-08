/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class RuntimeArchiveControllerTest {
    private final UUID org = UUID.randomUUID(),
            user = UUID.randomUUID(),
            agent = UUID.randomUUID(),
            session = UUID.randomUUID();
    private final TenantContext tenant =
            new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final PgSessionArchiveStore archive = mock(PgSessionArchiveStore.class);
    private final TenantResolver resolver = mock(TenantResolver.class);
    private final RuntimeArchiveController controller =
            new RuntimeArchiveController(sessions, archive, resolver, new SaasProperties());

    @BeforeEach
    void prepare() {
        when(resolver.resolve(any())).thenReturn(tenant);
    }

    @Test
    void foreignSessionIsRejectedBeforeSourceRead() {
        when(sessions.findByIdAndOrgIdAndUserIdAndAgentId(session, org, user, agent))
                .thenReturn(Optional.empty());
        StepVerifier.create(
                        controller.page(
                                null,
                                agent.toString(),
                                session.toString(),
                                null,
                                null,
                                0L,
                                null,
                                10,
                                true))
                .expectErrorSatisfies(
                        error ->
                                assertThat(
                                                ((ResponseStatusException) error)
                                                        .getStatusCode()
                                                        .value())
                                        .isEqualTo(404))
                .verify();
        verifyNoInteractions(archive);
    }

    @Test
    void ownerScopeAndPaginationComeFromTrustedIdentity() {
        when(sessions.findByIdAndOrgIdAndUserIdAndAgentId(session, org, user, agent))
                .thenReturn(Optional.of(new ChatSessionEntity()));
        when(archive.page(
                        tenant,
                        agent,
                        session,
                        "assistant",
                        session.toString(),
                        2L,
                        null,
                        10,
                        true))
                .thenReturn(new PgSessionArchiveStore.Page(List.of(), 2L, null, false));
        StepVerifier.create(
                        controller.page(
                                null,
                                agent.toString(),
                                session.toString(),
                                null,
                                null,
                                2L,
                                null,
                                10,
                                true))
                .expectNextCount(1)
                .verifyComplete();
        verify(archive)
                .page(tenant, agent, session, "assistant", session.toString(), 2L, null, 10, true);
    }

    @Test
    void previewFlagIsPassedToOwnedArchiveRead() {
        when(sessions.findByIdAndOrgIdAndUserIdAndAgentId(session, org, user, agent))
                .thenReturn(Optional.of(new ChatSessionEntity()));
        when(archive.page(
                        tenant,
                        agent,
                        session,
                        "assistant",
                        session.toString(),
                        0L,
                        null,
                        10,
                        false))
                .thenReturn(new PgSessionArchiveStore.Page(List.of(), 0L, null, false));
        StepVerifier.create(
                        controller.page(
                                null,
                                agent.toString(),
                                session.toString(),
                                null,
                                null,
                                0L,
                                null,
                                10,
                                false))
                .expectNextCount(1)
                .verifyComplete();
        verify(archive)
                .page(tenant, agent, session, "assistant", session.toString(), 0L, null, 10, false);
    }

    @Test
    void malformedWindowDoesNotReachDatabase() {
        assertThatThrownBy(
                        () ->
                                controller.page(
                                        null,
                                        agent.toString(),
                                        session.toString(),
                                        null,
                                        null,
                                        0L,
                                        4L,
                                        10,
                                        true))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(sessions, archive);
    }
}
