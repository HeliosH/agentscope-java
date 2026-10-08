/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateNamespace;
import io.agentscope.core.state.ConversationCommitter;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ConversationCommitBarrierTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void stateNamespacesIsolateHistoryAndLateOldWritesWithoutChangingRuntimeSessionId() {
        var store = new InMemoryAgentStateStore();
        var agent =
                agent(
                        model(Flux.just(text("old answer")), Flux.just(text("new answer"))),
                        new Toolkit(),
                        store);
        var old = context(null);
        agent.call(List.of(question()), old).block(TIMEOUT);
        var current = context(null);
        current.put(AgentStateNamespace.class, new AgentStateNamespace("generation-1"));
        agent.call(List.of(question()), current).block(TIMEOUT);
        var state =
                store.get(
                                "employee",
                                "barrier-session@generation-1",
                                "agent_state",
                                AgentState.class)
                        .orElseThrow();
        assertFalse(hasText(state.getContext(), "old answer"));
        assertTrue(hasText(state.getContext(), "new answer"));
        String before = StepSnapshot.fingerprint(state.getContext());
        int initialSize = state.getContext().size();
        agent.call(List.of(question()), old).block(TIMEOUT);
        assertEquals(
                before,
                StepSnapshot.fingerprint(
                        store.get(
                                        "employee",
                                        "barrier-session@generation-1",
                                        "agent_state",
                                        AgentState.class)
                                .orElseThrow()
                                .getContext()));
        assertEquals("barrier-session", current.getSessionId());
        agent.call(List.of(question()), current).block(TIMEOUT);
        assertTrue(
                store.get(
                                        "employee",
                                        "barrier-session@generation-1",
                                        "agent_state",
                                        AgentState.class)
                                .orElseThrow()
                                .getContext()
                                .size()
                        > initialSize);
    }

    @Test
    void invalidStorageNamespaceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentStateNamespace("../old"));
        assertThrows(IllegalArgumentException.class, () -> new AgentStateNamespace(""));
    }

    @Test
    void permissionRestoreAndExplicitStateAccessUseTheSameStorageNamespace() {
        var store = new InMemoryAgentStateStore();
        var agent =
                agent(
                        model(Flux.just(text("done")), Flux.just(text("done"))),
                        new Toolkit(),
                        store);
        var ctx = context(null);
        ctx.put(AgentStateNamespace.class, new AgentStateNamespace("generation-1"));
        agent.setPermissionMode(ctx, PermissionMode.BYPASS);
        assertEquals(
                PermissionMode.BYPASS, agent.getAgentState(ctx).getPermissionContext().getMode());
        agent.setPermissionContext(
                ctx,
                agent.getAgentState(ctx).getPermissionContext().withMode(PermissionMode.DEFAULT));
        agent.getAgentState(ctx).contextMutable().add(question());
        agent.saveAgentState(ctx);
        var saved =
                store.get(
                                "employee",
                                "barrier-session@generation-1",
                                "agent_state",
                                AgentState.class)
                        .orElseThrow();
        assertEquals(PermissionMode.DEFAULT, saved.getPermissionContext().getMode());
        assertEquals(1, saved.getContext().size());
        assertTrue(
                store.get("employee", "barrier-session", "agent_state", AgentState.class)
                        .isEmpty());
    }

    @Test
    void journalThenSourceThenCheckpointThenFinalSourceThenState() {
        var order = new CopyOnWriteArrayList<String>();
        var source = new AtomicReference<List<Msg>>(List.of());
        var stateStore = spy(new InMemoryAgentStateStore());
        doAnswer(
                        call -> {
                            order.add("state");
                            return call.callRealMethod();
                        })
                .when(stateStore)
                .save(any(), any(), eq("agent_state"), any(State.class));
        RuntimeContext context = context(journal(order));
        context.put(
                ConversationCommitter.class,
                (rc, name, messages) -> {
                    source.set(messages);
                    order.add(hasText(messages, "done") ? "source-final" : "source-tool");
                });
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    assertTrue(
                            source.get().stream()
                                    .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
                    assertTrue(order.contains("journal"));
                    order.add("checkpoint");
                    return new ContextCheckpointStore.StoredCheckpoint(1, draft.historyHash());
                });
        var agent =
                agent(
                        model(Flux.just(tool()), Flux.just(text("done"))),
                        toolkit(Mono.just(ToolResultBlock.text("executed"))),
                        stateStore);
        assertEquals(
                "done", agent.call(List.of(question()), context).block(TIMEOUT).getTextContent());
        assertEquals(
                List.of("journal", "source-tool", "checkpoint", "source-final", "state"), order);
    }

    @Test
    void checkpointBackendCanRetainMoreThanLegacyTwentyMessages() {
        var captured = new AtomicReference<ContextCheckpointStore.Draft>();
        RuntimeContext context = context(null);
        context.put(
                ContextCheckpointStore.class,
                new ContextCheckpointStore() {
                    @Override
                    public StoredCheckpoint save(Draft draft) {
                        captured.set(draft);
                        return new StoredCheckpoint(1, draft.historyHash());
                    }

                    @Override
                    public List<Msg> retainedWindow(List<Msg> workingWindow) {
                        return List.copyOf(workingWindow);
                    }
                });
        var agent =
                agent(
                        model(Flux.just(tool()), Flux.just(text("done"))),
                        toolkit(Mono.just(ToolResultBlock.text("executed"))),
                        new InMemoryAgentStateStore());
        List<Msg> input =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(
                                i ->
                                        Msg.builder()
                                                .id("working-" + i)
                                                .role(MsgRole.USER)
                                                .textContent("question-" + i)
                                                .build())
                        .toList();
        agent.call(input, context).block(TIMEOUT);
        assertEquals(32, captured.get().retainedTail().size());
        assertEquals("working-0", captured.get().retainedTail().get(0).getId());
    }

    @Test
    void freshConfirmationInvocationCreatesExecutionSnapshotAndCheckpointBeforeModel() {
        var order = new CopyOnWriteArrayList<String>();
        var captured = new AtomicReference<ContextCheckpointStore.Draft>();
        var stateStore = new InMemoryAgentStateStore();
        RuntimeContext context = context(journal(order));
        AgentState stored =
                AgentState.builder()
                        .userId(context.getUserId())
                        .sessionId(context.getSessionId())
                        .build();
        stored.contextMutable().add(question());
        var asking =
                ToolUseBlock.builder()
                        .id("call")
                        .name("effect")
                        .input(Map.of())
                        .content("{}")
                        .state(ToolCallState.ASKING)
                        .build();
        stored.contextMutable()
                .add(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(asking)
                                .build());
        stateStore.save(context.getUserId(), context.getSessionId(), "agent_state", stored);
        Model model = model(Flux.just(text("done")), Flux.just(text("unused")));
        context.put(ConversationCommitter.class, (rc, name, messages) -> order.add("source"));
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    assertTrue(order.contains("journal"));
                    assertTrue(order.contains("source"));
                    verify(model, never()).stream(any(), any(), any());
                    captured.set(draft);
                    return new ContextCheckpointStore.StoredCheckpoint(1, draft.historyHash());
                });
        var agent = agent(model, toolkit(Mono.just(ToolResultBlock.text("executed"))), stateStore);
        Msg confirmation =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("approve")
                        .metadata(
                                Map.of(
                                        Msg.METADATA_CONFIRM_RESULTS,
                                        List.of(new ConfirmResult(true, asking))))
                        .build();
        assertEquals(
                "done", agent.call(List.of(confirmation), context).block(TIMEOUT).getTextContent());
        assertTrue(captured.get().stepId().startsWith("resume-"));
        assertEquals(context.get(StepSnapshot.Identity.class), captured.get().identity());
        assertTrue(
                captured.get().retainedTail().stream()
                        .anyMatch(msg -> msg.hasContentBlocks(ToolResultBlock.class)));
        assertEquals(1, order.stream().filter("journal"::equals).count());
    }

    @Test
    void sourceFailureAfterJournalPreventsCheckpointAndContinuation() {
        var order = new CopyOnWriteArrayList<String>();
        RuntimeContext context = context(journal(order));
        var failure = new IllegalStateException("source unavailable");
        context.put(
                ConversationCommitter.class,
                (rc, name, messages) -> {
                    throw failure;
                });
        var checkpoints = new AtomicInteger();
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    checkpoints.incrementAndGet();
                    return new ContextCheckpointStore.StoredCheckpoint(1, draft.historyHash());
                });
        Model model = model(Flux.just(tool()), Flux.just(text("must not run")));
        var stateStore = spy(new InMemoryAgentStateStore());
        var agent = agent(model, toolkit(Mono.just(ToolResultBlock.text("executed"))), stateStore);
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(question()), context).block(TIMEOUT)));
        assertEquals(List.of("journal"), order);
        assertEquals(0, checkpoints.get());
        verify(model).stream(any(), any(), any());
        verify(stateStore, never()).save(any(), any(), eq("agent_state"), any(State.class));
    }

    @Test
    void checkpointFailureDoesNotUndoCommittedSourceOrRepeatTool() {
        var order = new CopyOnWriteArrayList<String>();
        RuntimeContext context = context(journal(order));
        var source = new AtomicReference<List<Msg>>(List.of());
        context.put(
                ConversationCommitter.class,
                (rc, name, messages) -> {
                    source.set(messages);
                    order.add("source");
                });
        var failure = new IllegalStateException("checkpoint unavailable");
        context.put(
                ContextCheckpointStore.class,
                draft -> {
                    order.add("checkpoint");
                    throw failure;
                });
        var calls = new AtomicInteger();
        var agent =
                agent(
                        model(Flux.just(tool()), Flux.just(text("unused"))),
                        toolkit(
                                Mono.fromSupplier(
                                        () -> {
                                            calls.incrementAndGet();
                                            return ToolResultBlock.text("executed");
                                        })),
                        new InMemoryAgentStateStore());
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(question()), context).block(TIMEOUT)));
        assertEquals(List.of("journal", "source", "checkpoint"), order);
        assertEquals(1, calls.get());
        assertTrue(source.get().stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
    }

    @Test
    void finalSourceFailurePreventsSnapshotAndAgentResultEvent() {
        RuntimeContext context = context(null);
        var failure = new IllegalStateException("final source unavailable");
        context.put(
                ConversationCommitter.class,
                (rc, name, messages) -> {
                    throw failure;
                });
        var stateStore = spy(new InMemoryAgentStateStore());
        var resultSeen = new AtomicBoolean();
        var agent =
                agent(
                        model(Flux.just(text("done")), Flux.just(text("unused"))),
                        new Toolkit(),
                        stateStore);
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.streamEvents(List.of(question()), context)
                                        .doOnNext(
                                                event -> {
                                                    if (event instanceof AgentResultEvent)
                                                        resultSeen.set(true);
                                                })
                                        .collectList()
                                        .block(TIMEOUT)));
        assertFalse(resultSeen.get());
        verify(stateStore, never()).save(any(), any(), eq("agent_state"), any(State.class));
    }

    @Test
    void cancellationAtStartDoesNotLaunchModel() {
        Model model = model(Flux.never(), Flux.just(text("unused")));
        var agent = agent(model, new Toolkit(), new InMemoryAgentStateStore());
        agent.streamEvents(List.of(question()), context(null)).take(1).blockLast(TIMEOUT);
        verify(model, never()).stream(any(), any(), any());
    }

    @Test
    void cancellationStopsModelAndReleasesSameSessionSerialization() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        Model model =
                model(
                        Flux.<ChatResponse>never()
                                .doOnSubscribe(s -> started.countDown())
                                .doOnCancel(cancelled::countDown),
                        Flux.just(text("next call")));
        var agent = agent(model, new Toolkit(), new InMemoryAgentStateStore());
        RuntimeContext context = context(null);
        var subscription = agent.streamEvents(List.of(question()), context).subscribe();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertEquals(
                "next call",
                agent.call(List.of(question()), context).block(TIMEOUT).getTextContent());
    }

    @Test
    void frozenBoundaryDoesNotReadLaterMutableSessionState() {
        RuntimeContext context = context(null);
        var state = AgentState.builder().sessionId(context.getSessionId()).build();
        context.setAgentState(state);
        Msg original = question();
        state.contextMutable().add(original);
        var committed = new AtomicReference<List<Msg>>();
        context.put(ConversationCommitter.class, (rc, name, messages) -> committed.set(messages));
        ConversationCommitter.freezeBoundary(context);
        state.contextMutable().add(question());
        ConversationCommitter.commitBoundary(context, "assistant");
        assertEquals(List.of(original), committed.get());
        assertThrows(UnsupportedOperationException.class, () -> committed.get().add(question()));
    }

    @Test
    void cancellationStopsToolAndRecordsUnknownWriteOutcome() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        ToolExecutionJournal journal = mock(ToolExecutionJournal.class);
        when(journal.prepare(any())).thenReturn(ToolExecutionJournal.PrepareResult.execute());
        var terminal = new AtomicReference<ToolExecutionJournal.TerminalStatus>();
        doAnswer(
                        call -> {
                            terminal.set(call.getArgument(1));
                            return null;
                        })
                .when(journal)
                .complete(any(), any(), any(), any());
        var agent =
                agent(
                        model(Flux.just(tool()), Flux.just(text("unused"))),
                        toolkit(
                                Mono.<ToolResultBlock>never()
                                        .doOnSubscribe(s -> started.countDown())
                                        .doOnCancel(cancelled::countDown)),
                        new InMemoryAgentStateStore());
        var subscription = agent.streamEvents(List.of(question()), context(journal)).subscribe();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertEquals(ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN, terminal.get());
    }

    private static ToolExecutionJournal journal(List<String> order) {
        ToolExecutionJournal journal = mock(ToolExecutionJournal.class);
        when(journal.prepare(any())).thenReturn(ToolExecutionJournal.PrepareResult.execute());
        doAnswer(
                        call -> {
                            assertEquals(
                                    ToolExecutionJournal.TerminalStatus.SUCCEEDED,
                                    call.getArgument(1));
                            order.add("journal");
                            return null;
                        })
                .when(journal)
                .complete(any(), any(), any(), any());
        return journal;
    }

    private static RuntimeContext context(ToolExecutionJournal journal) {
        var context =
                RuntimeContext.builder().userId("employee").sessionId("barrier-session").build();
        context.put(
                StepSnapshot.Identity.class,
                new StepSnapshot.Identity("run", "agent-run", "task", "attempt"));
        context.put(ExecutionLeaseSnapshot.class, new ExecutionLeaseSnapshot("worker"));
        if (journal != null) context.put(ToolExecutionJournal.class, journal);
        return context;
    }

    private static ReActAgent agent(
            Model model, Toolkit toolkit, InMemoryAgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .build();
    }

    private static Model model(Flux<ChatResponse> first, Flux<ChatResponse> second) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("barrier-fixture");
        when(model.stream(any(), any(), any())).thenReturn(first, second);
        return model;
    }

    private static Toolkit toolkit(Mono<ToolResultBlock> execution) {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("effect")
                                .description("fixture")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow("fixture"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return execution;
                    }
                });
        return toolkit;
    }

    private static ChatResponse tool() {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("effect")
                                        .input(Map.of())
                                        .build()))
                .build();
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    private static Msg question() {
        return Msg.builder().role(MsgRole.USER).textContent("run task").build();
    }

    private static boolean hasText(List<Msg> messages, String value) {
        return messages.stream()
                .anyMatch(
                        msg ->
                                msg.getRole() == MsgRole.ASSISTANT
                                        && value.equals(msg.getTextContent()));
    }
}
