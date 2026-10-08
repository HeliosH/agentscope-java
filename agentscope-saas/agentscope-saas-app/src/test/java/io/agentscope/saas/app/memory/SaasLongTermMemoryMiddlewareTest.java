/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.core.memory.mem0.Mem0SearchResponse;
import io.agentscope.core.memory.mem0.Mem0SearchResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SaasLongTermMemoryMiddlewareTest {
    private static final String ORG = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String SESSION = "session-1";
    private final Mem0Client mem0 = mock(Mem0Client.class);
    private final MemoryLedger ledger = mock(MemoryLedger.class);
    private final Agent agent = mock(Agent.class);
    private final Msg reply = text(MsgRole.ASSISTANT, "I will keep answers concise.");
    private SaasLongTermMemoryMiddleware middleware;

    @BeforeEach
    void setUp() {
        when(mem0.search(any())).thenReturn(Mono.just(new Mem0SearchResponse()));
        middleware = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5, ledger);
    }

    @Test
    void retrievesMemoriesButOnlyCommitsOriginalTurnAndFinalAnswer() {
        Mem0SearchResult memory = new Mem0SearchResult();
        memory.setMemory("User prefers concise answers");
        Mem0SearchResponse response = new Mem0SearchResponse();
        response.setResults(List.of(memory));
        when(mem0.search(any())).thenReturn(Mono.just(response));
        AtomicReference<AgentInput> captured = new AtomicReference<>();
        Msg user = text(MsgRole.USER, "remember my preference");
        var input =
                new AgentInput(
                        List.of(
                                text(MsgRole.SYSTEM, "secret prompt"),
                                text(MsgRole.USER, "old turn"),
                                text(MsgRole.TOOL, "secret tool result"),
                                user));

        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input,
                                enhanced -> {
                                    captured.set(enhanced);
                                    return Flux.just(new AgentResultEvent(reply));
                                }))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(captured.get().msgs()).hasSize(5);
        assertThat(captured.get().msgs().get(4).getTextContent()).contains("<long_term_memory>");
        ArgumentCaptor<List<Mem0Message>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(ledger).recordPending(any(), any(), any(), messages.capture(), metadata.capture());
        assertThat(messages.getValue())
                .extracting(Mem0Message::getContent)
                .containsExactly("remember my preference", reply.getTextContent());
        assertThat(metadata.getValue())
                .containsEntry("org_id", ORG)
                .containsEntry("source_message_ids", List.of(user.getId()));
        verify(mem0, never()).add(any());
    }

    @Test
    void retrievalFailureDoesNotBreakRunOrSkipSourceCommit() {
        when(mem0.search(any())).thenReturn(Mono.error(new IllegalStateException("offline")));
        AtomicReference<AgentInput> captured = new AtomicReference<>();
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input(),
                                value -> {
                                    captured.set(value);
                                    return Flux.just(new AgentResultEvent(reply));
                                }))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(captured.get().msgs()).hasSize(1);
        verify(ledger).recordPending(any(), any(), any(), any(), any());
        verify(mem0, never()).add(any());
    }

    @Test
    void emptySearchDoesNotInjectARecallMessage() {
        AtomicReference<AgentInput> captured = new AtomicReference<>();
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input(),
                                value -> {
                                    captured.set(value);
                                    return Flux.just(new AgentResultEvent(reply));
                                }))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(captured.get().msgs()).hasSize(1);
    }

    @Test
    void noTenantSkipsAllMemoryWork() {
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                RuntimeContext.empty(),
                                input(),
                                ignored -> Flux.just(new AgentResultEvent(reply))))
                .expectNextCount(1)
                .verifyComplete();
        verifyNoInteractions(mem0, ledger);
    }

    @Test
    void failedCallDoesNotCreateProjectionSourceEvenAfterAResultEvent() {
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input(),
                                ignored ->
                                        Flux.concat(
                                                Flux.just(new AgentResultEvent(reply)),
                                                Flux.error(
                                                        new IllegalStateException(
                                                                "archive failed")))))
                .expectNextCount(1)
                .expectErrorMessage("archive failed")
                .verify();
        verifyNoInteractions(ledger);
        verify(mem0, never()).add(any());
    }

    @Test
    void cancellationAndSuspensionDoNotCreateProjectionSource() {
        StepVerifier.create(middleware.onAgent(agent, context(), input(), ignored -> Flux.never()))
                .thenCancel()
                .verify();
        StepVerifier.create(middleware.onAgent(agent, context(), input(), ignored -> Flux.empty()))
                .verifyComplete();
        verifyNoInteractions(ledger);
    }

    @Test
    void childResultIsNotCommittedAsParentReply() {
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input(),
                                ignored ->
                                        Flux.just(
                                                new AgentResultEvent(reply)
                                                        .withSource("main/child"))))
                .expectNextCount(1)
                .verifyComplete();
        verifyNoInteractions(ledger);
    }

    @Test
    void failedLedgerCommitNeverSendsUncommittedContentToMem0() {
        when(ledger.recordPending(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database offline"));
        StepVerifier.create(
                        middleware.onAgent(
                                agent,
                                context(),
                                input(),
                                ignored -> Flux.just(new AgentResultEvent(reply))))
                .expectNextCount(1)
                .verifyComplete();
        verify(ledger).recordPending(any(), any(), any(), any(), any());
        verify(mem0, never()).add(any());
    }

    @Test
    void noLedgerConstructorIsRetrievalOnlyNotFireAndForget() {
        var retrievalOnly = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        StepVerifier.create(
                        retrievalOnly.onAgent(
                                agent,
                                context(),
                                input(),
                                ignored -> Flux.just(new AgentResultEvent(reply))))
                .expectNextCount(1)
                .verifyComplete();
        verify(mem0, never()).add(any());
    }

    private static Msg text(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }

    private static AgentInput input() {
        return new AgentInput(List.of(text(MsgRole.USER, "Remember concise answers")));
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .userId(USER)
                .sessionId(SESSION)
                .put(
                        TenantContext.ATTR_KEY,
                        new TenantContext(ORG, USER, "member", "standard", 2, 0L))
                .build();
    }
}
