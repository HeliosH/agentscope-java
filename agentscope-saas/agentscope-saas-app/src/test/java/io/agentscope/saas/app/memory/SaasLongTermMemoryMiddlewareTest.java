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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.mem0.Mem0AddResponse;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.core.memory.mem0.Mem0SearchResponse;
import io.agentscope.core.memory.mem0.Mem0SearchResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies {@link SaasLongTermMemoryMiddleware}: memory retrieval is injected pre-call, the
 * conversation is recorded post-call, tenant-less calls skip LTM, and Mem0 failures degrade
 * gracefully without breaking the agent run.
 */
class SaasLongTermMemoryMiddlewareTest {

    private static final String ORG = "org-uuid-1";
    private static final String USER = "user-uuid-1";
    private static final String SESSION = "session-uuid-1";

    private final Mem0Client mem0 = mock(Mem0Client.class);

    /** Builds a RuntimeContext carrying the tenant via the ATTR_KEY string slot (survives rebuild). */
    private static RuntimeContext ctxWithTenant() {
        TenantContext tc = new TenantContext(ORG, USER, "member", "standard", 2, 0L);
        return RuntimeContext.builder()
                .userId(USER)
                .sessionId(SESSION)
                .put(TenantContext.ATTR_KEY, tc)
                .build();
    }

    private static AgentInput input(String userText) {
        return new AgentInput(
                List.of(Msg.builder().role(MsgRole.USER).textContent(userText).build()));
    }

    /** A next-middleware that captures the input it receives and emits a single completion event. */
    private static final class CapturingNext implements Function<AgentInput, Flux<AgentEvent>> {
        final AtomicReference<AgentInput> captured = new AtomicReference<>();

        @Override
        public Flux<AgentEvent> apply(AgentInput input) {
            captured.set(input);
            return Flux.empty();
        }
    }

    @Test
    void retrievesAndInjectsMemoriesBeforeCall() {
        when(mem0.search(any())).thenReturn(Mono.just(searchResponse("User prefers homestays")));
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));

        SaasLongTermMemoryMiddleware mw = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(
                        mw.onAgent(
                                mock(Agent.class), ctxWithTenant(), input("travel prefs?"), next))
                .verifyComplete();

        // The input passed to next must have the memory message appended.
        List<Msg> msgs = next.captured.get().msgs();
        assertThat(msgs).hasSize(2);
        Msg memoryMsg = msgs.get(1);
        assertThat(memoryMsg.getRole()).isEqualTo(MsgRole.USER);
        String memoryText = memoryMsg.getTextContent();
        assertThat(memoryText).contains("<long_term_memory>");
        assertThat(memoryText).contains("User prefers homestays");

        verify(mem0, times(1)).search(any());
    }

    @Test
    void recordsConversationAfterCall() {
        when(mem0.search(any())).thenReturn(Mono.just(new Mem0SearchResponse()));
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));

        SaasLongTermMemoryMiddleware mw = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(mw.onAgent(mock(Agent.class), ctxWithTenant(), input("hello"), next))
                .verifyComplete();

        // record runs async in doFinally; give it a moment to fire.
        verify(mem0, timeout(2000).times(1)).add(any());
    }

    @Test
    void recordsMemoryEventBeforeMem0ProjectionAndMarksSynced() {
        when(mem0.search(any())).thenReturn(Mono.just(new Mem0SearchResponse()));
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));
        MemoryLedger ledger = mock(MemoryLedger.class);
        MemoryLedger.MemoryEventRef ref = new MemoryLedger.MemoryEventRef(UUID.randomUUID(), ORG);
        when(ledger.recordPending(
                        any(TenantContext.class),
                        any(String.class),
                        any(String.class),
                        any(List.class),
                        any(Map.class)))
                .thenReturn(Optional.of(ref));

        SaasLongTermMemoryMiddleware mw =
                new SaasLongTermMemoryMiddleware(mem0, "assistant", 5, ledger);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(mw.onAgent(mock(Agent.class), ctxWithTenant(), input("hello"), next))
                .verifyComplete();

        verify(ledger, timeout(2000))
                .recordPending(
                        any(TenantContext.class),
                        any(String.class),
                        any(String.class),
                        any(List.class),
                        any(Map.class));
        verify(ledger, timeout(2000)).markSynced(ref);
        verify(ledger, never()).markFailed(any(), any());
    }

    @Test
    void marksMemoryEventFailedWhenMem0ProjectionFails() {
        RuntimeException mem0Failure = new RuntimeException("projection down");
        when(mem0.search(any())).thenReturn(Mono.just(new Mem0SearchResponse()));
        when(mem0.add(any())).thenReturn(Mono.error(mem0Failure));
        MemoryLedger ledger = mock(MemoryLedger.class);
        MemoryLedger.MemoryEventRef ref = new MemoryLedger.MemoryEventRef(UUID.randomUUID(), ORG);
        when(ledger.recordPending(
                        any(TenantContext.class),
                        any(String.class),
                        any(String.class),
                        any(List.class),
                        any(Map.class)))
                .thenReturn(Optional.of(ref));

        SaasLongTermMemoryMiddleware mw =
                new SaasLongTermMemoryMiddleware(mem0, "assistant", 5, ledger);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(mw.onAgent(mock(Agent.class), ctxWithTenant(), input("hello"), next))
                .verifyComplete();

        verify(ledger, timeout(2000)).markFailed(ref, mem0Failure);
        verify(ledger, never()).markSynced(any());
    }

    @Test
    void retrieveFailureDegradesToOriginalInputWithoutBreakingRun() {
        when(mem0.search(any())).thenReturn(Mono.error(new RuntimeException("mem0 down")));
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));

        SaasLongTermMemoryMiddleware mw = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(mw.onAgent(mock(Agent.class), ctxWithTenant(), input("hi"), next))
                .verifyComplete();

        // On retrieve failure, the original (unenhanced) input reaches next — no memory appended.
        assertThat(next.captured.get().msgs()).hasSize(1);
        verify(mem0, timeout(2000).times(1)).add(any());
    }

    @Test
    void noTenantContextSkipsLtm() {
        SaasLongTermMemoryMiddleware mw = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        CapturingNext next = new CapturingNext();
        RuntimeContext anonymousCtx = RuntimeContext.builder().sessionId(SESSION).build();

        StepVerifier.create(mw.onAgent(mock(Agent.class), anonymousCtx, input("hi"), next))
                .verifyComplete();

        // No tenant -> middleware is a pass-through; Mem0 never called.
        assertThat(next.captured.get().msgs()).hasSize(1);
        verify(mem0, never()).search(any());
        verify(mem0, never()).add(any());
    }

    @Test
    void emptySearchResultsDoNotInjectMemoryMessage() {
        when(mem0.search(any())).thenReturn(Mono.just(new Mem0SearchResponse()));
        when(mem0.add(any())).thenReturn(Mono.just(new Mem0AddResponse()));

        SaasLongTermMemoryMiddleware mw = new SaasLongTermMemoryMiddleware(mem0, "assistant", 5);
        CapturingNext next = new CapturingNext();

        StepVerifier.create(mw.onAgent(mock(Agent.class), ctxWithTenant(), input("hi"), next))
                .verifyComplete();

        // No memories retrieved -> input unchanged (no memory message appended).
        assertThat(next.captured.get().msgs()).hasSize(1);
    }

    private static Mem0SearchResponse searchResponse(String... memories) {
        List<Mem0SearchResult> results = new ArrayList<>();
        for (String m : memories) {
            Mem0SearchResult r = new Mem0SearchResult();
            r.setMemory(m);
            results.add(r);
        }
        Mem0SearchResponse resp = new Mem0SearchResponse();
        resp.setResults(results);
        return resp;
    }
}
