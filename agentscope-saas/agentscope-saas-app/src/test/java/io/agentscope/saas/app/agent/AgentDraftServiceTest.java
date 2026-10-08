/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.PurposeBindableModel;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

class AgentDraftServiceTest {
    private final PurposeBindableModel router = mock(PurposeBindableModel.class);
    private final Model bound = mock(Model.class);
    private final AgentDraftService service =
            new AgentDraftService(
                    Optional.of(router), new DefaultResourceLoader(), new ObjectMapper());
    private final TenantContext tenant =
            new TenantContext(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "member",
                    "standard",
                    2,
                    100000);

    @Test
    void draftsWithTrustedTenantAndGovernedOutputLimit() {
        when(router.bindToPurpose(any(), eq(PurposeBindableModel.Purpose.REASONING)))
                .thenReturn(bound);
        when(bound.stream(any(), any(), any())).thenReturn(Flux.just(response("stop")));
        assertThat(service.draft(tenant, "Research assistant").block().name())
                .isEqualTo("Research");
        var context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(router).bindToPurpose(context.capture(), eq(PurposeBindableModel.Purpose.REASONING));
        assertThat(TenantContext.from(context.getValue())).isEqualTo(tenant);
        assertThat(context.getValue().getUserId()).isEqualTo(tenant.userId());
        var options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(bound).stream(any(), any(), options.capture());
        assertThat(options.getValue().getMaxTokens()).isEqualTo(2048);
        verify(router, never()).stream(any(), any(), any());
    }

    @Test
    void rejectsTruncatedJsonEvenWhenItWouldOtherwiseParse() {
        when(router.bindToPurpose(any(), any())).thenReturn(bound);
        when(bound.stream(any(), any(), any())).thenReturn(Flux.just(response("length")));
        assertThatThrownBy(() -> service.draft(tenant, "Research assistant").block())
                .hasMessageContaining("incomplete draft");
    }

    @Test
    void governedDraftRequiresAuthenticatedScope() {
        assertThatThrownBy(() -> service.draft("Research assistant").block())
                .hasMessageContaining("401");
        verify(router, never()).bindToPurpose(any(), any());
    }

    private static ChatResponse response(String reason) {
        return ChatResponse.builder()
                .finishReason(reason)
                .content(
                        List.of(
                                TextBlock.builder()
                                        .text(
                                                "{\"name\":\"Research\",\"description\":\"Research"
                                                        + " assistant\"}")
                                        .build()))
                .build();
    }
}
