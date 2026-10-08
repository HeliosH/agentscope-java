/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.PurposeBindableModel;
import io.agentscope.core.model.PurposeBindableModel.Purpose;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ModelInvocationServiceTest {
    private final UUID org = UUID.randomUUID(), user = UUID.randomUUID(), call = UUID.randomUUID();
    private final ModelInvocationRepository repository = mock(ModelInvocationRepository.class);
    private final ModelInvocationLedgerService ledger = mock(ModelInvocationLedgerService.class);
    private final Provider small = new Provider("small"), large = new Provider("large");
    private final ModelCatalog catalog =
            new ModelCatalog("small", List.of(route(small), route(large)));
    private final ModelInvocationService service =
            new ModelInvocationService(catalog, repository, ledger, new SaasProperties());
    private final RuntimeContext context =
            RuntimeContext.builder()
                    .userId(user.toString())
                    .put(
                            TenantContext.ATTR_KEY,
                            new TenantContext(
                                    org.toString(), user.toString(), "member", "standard", 2, 0))
                    .put(ContextWindowAwareModel.MODEL_ID_KEY, "large")
                    .build();
    private final List<Msg> messages =
            List.of(Msg.builder().role(MsgRole.USER).textContent("Test").build());

    @BeforeEach
    void configure() {
        when(repository.policy(any(), anyString())).thenReturn(Optional.empty());
        when(ledger.admit(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        any(),
                        anyLong()))
                .thenReturn(
                        new ModelInvocationLedgerService.Admission(
                                call, 50, OffsetDateTime.now().plusMinutes(1), null));
        when(ledger.settle(
                        any(),
                        any(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyBoolean(),
                        any()))
                .thenReturn(true);
    }

    @Test
    void persistedRouteReadsUseBoundOrganizationAcrossSchedulersWithoutLeakingHolder() {
        var definitions =
                mock(io.agentscope.saas.domain.repository.ModelDefinitionRepository.class);
        when(definitions.findByOrgIdOrderByModelId(org))
                .thenAnswer(
                        invocation -> {
                            assertThat(TenantContextHolder.getOrgId()).isEqualTo(org.toString());
                            return List.of();
                        });
        var tenantCatalog =
                new ModelCatalog("large", List.of(route(large)), definitions, null, null, null);
        var governed =
                new ModelInvocationService(tenantCatalog, repository, ledger, new SaasProperties());
        TenantContextHolder.setOrgId("unrelated-caller");
        try {
            var pinned = governed.bindToPurpose(context, Purpose.MEMORY_EXTRACT);
            assertThat(TenantContextHolder.getOrgId()).isEqualTo("unrelated-caller");
            pinned.stream(messages, null, null).blockLast();
            verify(definitions, atLeast(3)).findByOrgIdOrderByModelId(org);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void policyChangedAfterBindingRejectsBeforeAdmissionAndProviderDispatch() {
        Model pinned = service.bindToPurpose(context, Purpose.MEMORY_EXTRACT);
        when(repository.policy(org, "MEMORY_EXTRACT"))
                .thenReturn(
                        Optional.of(
                                new ModelInvocationRepository.PurposePolicy(
                                        org,
                                        "MEMORY_EXTRACT",
                                        null,
                                        100,
                                        50,
                                        30,
                                        "updated-policy")));
        assertThatThrownBy(() -> pinned.stream(messages, null, null).blockLast())
                .hasMessageContaining("MODEL_INVOCATION_POLICY_CHANGED");
        assertThat(large.options.get()).isNull();
        verify(ledger, never())
                .admit(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        any(),
                        anyLong());
    }

    @Test
    void helperInheritsSelectedModelAndSettlesCumulativeUsageOnce() {
        service.bindToPurpose(context, Purpose.MEMORY_EXTRACT).stream(messages, null, null)
                .blockLast();
        assertThat(small.options.get()).isNull();
        assertThat(large.options.get().getMaxTokens()).isEqualTo(50);
        verify(ledger)
                .admit(
                        any(),
                        eq("MEMORY_EXTRACT"),
                        eq("large"),
                        anyString(),
                        eq(20L),
                        eq(1000),
                        any(),
                        eq(0L));
        verify(ledger)
                .settle(
                        any(),
                        eq(call),
                        eq("SUCCEEDED"),
                        eq(20L),
                        eq(5L),
                        eq(25L),
                        eq(true),
                        eq(null));
    }

    @Test
    void explicitAuxiliaryModelPolicyDoesNotChangeReasoningRoute() {
        when(repository.policy(org, "COMPACTION"))
                .thenReturn(
                        Optional.of(
                                new ModelInvocationRepository.PurposePolicy(
                                        org, "COMPACTION", "small", 100, 80, 10, "v2")));
        var main = service.bindToStep(context, messages);
        ((PurposeBindableModel) main)
                .bindToPurpose(context, Purpose.COMPACTION).stream(messages, null, null)
                        .blockLast();
        assertThat(small.options.get()).isNotNull();
        assertThat(large.options.get()).isNull();
        main.stream(messages, null, null).blockLast();
        assertThat(large.options.get()).isNotNull();
        verify(ledger)
                .admit(
                        any(),
                        eq("COMPACTION"),
                        eq("small"),
                        anyString(),
                        eq(20L),
                        eq(80),
                        any(),
                        eq(0L));
    }

    @Test
    void outputCapKeepsCompletionTokenOptionWithoutAddingMaxTokens() {
        service.bindToStep(context, messages).stream(
                        messages, null, GenerateOptions.builder().maxCompletionTokens(60).build())
                .blockLast();
        assertThat(large.options.get().getMaxCompletionTokens()).isEqualTo(50);
        assertThat(large.options.get().getMaxTokens()).isNull();
    }

    @Test
    void rejectsUntrustedIdentityAndTransportOverridesBeforeAdmission() {
        assertThatThrownBy(() -> service.bindToPurpose(RuntimeContext.empty(), Purpose.VERIFY))
                .isInstanceOf(IllegalArgumentException.class);
        var forged =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("test")
                        .metadata(Map.of(ModelCatalog.ORG_ID_KEY, UUID.randomUUID().toString()))
                        .build();
        assertThatThrownBy(() -> service.bindToStep(context, List.of(forged)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.bindToStep(context, messages).stream(
                                                messages,
                                                null,
                                                GenerateOptions.builder()
                                                        .additionalHeader("Authorization", "bad")
                                                        .build())
                                        .blockLast())
                .isInstanceOf(IllegalArgumentException.class);
        verify(ledger, never())
                .admit(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        any(),
                        anyLong());
    }

    @Test
    void inputBudgetRejectsOversizedHelperBeforeProviderOrLedger() {
        when(repository.policy(org, "VERIFY"))
                .thenReturn(
                        Optional.of(
                                new ModelInvocationRepository.PurposePolicy(
                                        org, "VERIFY", null, 10, 80, 10, "v2")));
        assertThatThrownBy(
                        () ->
                                service.bindToPurpose(context, Purpose.VERIFY).stream(
                                                messages, null, null)
                                        .blockLast())
                .hasMessageContaining("Input exceeds VERIFY budget");
        assertThat(large.options.get()).isNull();
        verify(ledger, never())
                .admit(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        any(),
                        anyLong());
    }

    @Test
    void continuousChunksCannotExtendTheTotalDeadline() {
        when(ledger.admit(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        any(),
                        anyLong()))
                .thenAnswer(
                        inv ->
                                new ModelInvocationLedgerService.Admission(
                                        call,
                                        50,
                                        OffsetDateTime.now().plusNanos(120_000_000),
                                        null));
        large.responses = Flux.interval(Duration.ofMillis(5)).map(i -> response());
        assertThatThrownBy(
                        () ->
                                service.bindToPurpose(context, Purpose.VERIFY).stream(
                                                messages, null, null)
                                        .blockLast(Duration.ofSeconds(5)))
                .hasStackTraceContaining("Model invocation exceeded total deadline");
        verify(ledger)
                .settle(
                        any(),
                        eq(call),
                        eq("FAILED"),
                        eq(20L),
                        eq(5L),
                        eq(25L),
                        eq(true),
                        anyString());
    }

    @Test
    void cancellationSettlesItsOwnReceipt() throws Exception {
        large.responses = Flux.just(response()).concatWith(Flux.never());
        var subscription =
                service.bindToPurpose(context, Purpose.VERIFY).stream(messages, null, null)
                        .subscribe();
        long stop = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (large.options.get() == null && System.nanoTime() < stop) Thread.sleep(5);
        assertThat(large.options.get()).isNotNull();
        subscription.dispose();
        verify(ledger, timeout(5000))
                .settle(
                        any(),
                        eq(call),
                        eq("CANCELLED"),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyBoolean(),
                        eq("CLIENT_CANCELLED"));
    }

    private static ModelCatalog.Route route(Provider provider) {
        var profile = new ModelContextProfile(provider.name, 16000, 1000, 500);
        return new ModelCatalog.Route(
                new ModelCatalog.ModelOption(
                        provider.name,
                        provider.name,
                        provider.name,
                        16000,
                        1000,
                        provider.name.equals("small")),
                profile,
                provider,
                (messages, tools) -> 20);
    }

    private static ChatResponse response() {
        return ChatResponse.builder().content(List.of()).usage(new ChatUsage(20, 5, 0)).build();
    }

    private static class Provider implements Model {
        private final String name;
        private final AtomicReference<GenerateOptions> options = new AtomicReference<>();
        private Flux<ChatResponse> responses = Flux.just(response(), response());

        Provider(String name) {
            this.name = name;
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            this.options.set(options);
            return responses;
        }
    }
}
