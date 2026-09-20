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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ContextCheckpointStore;
import io.agentscope.core.tool.ExecutionLeaseSnapshot;
import io.agentscope.core.tool.RuntimeToolScope;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end ReAct loop: tool-use → tool-result → text-terminate, with two tools and a recording
 * middleware. Verifies the canonical event order and the final reply text.
 */
class ReActAgentNewLoopE2ETest {

    @Test
    void policyChangeWhileModelIsInFlightInvalidatesToolExecution() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = reactor.core.publisher.Sinks.<Void>one();
        var toolCalls = new AtomicInteger();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> {
                                    entered.countDown();
                                    return release.asMono()
                                            .thenMany(
                                                    Flux.just(
                                                            toolUseResponse(
                                                                    "revoked-1",
                                                                    "revocable",
                                                                    "value")));
                                },
                                () -> Flux.just(textResponse("denied safely"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AlwaysAllowTool("revocable") {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        toolCalls.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("executed"));
                    }
                });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("policy-revocation")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                        .build();
        Mono<Msg> running =
                agent.call(List.of())
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        var result = running.toFuture();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        agent.setPermissionContext(
                null,
                PermissionContextState.builder()
                        .addDenyRule(
                                "revocable",
                                new io.agentscope.core.permission.PermissionRule(
                                        "revocable",
                                        null,
                                        io.agentscope.core.permission.PermissionBehavior.DENY,
                                        "Emergency revocation"))
                        .build());
        release.tryEmitEmpty();
        Msg reply = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("denied safely", reply.getTextContent());
        assertEquals(0, toolCalls.get());
        assertTrue(
                agent.getAgentState().getContext().stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(
                                block ->
                                        "revoked-1".equals(block.getId())
                                                && block.getState() == ToolResultState.DENIED));
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);
        final AtomicInteger calls = new AtomicInteger(0);
        final List<List<String>> visibleTools = new ArrayList<>();

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            visibleTools.add(tools.stream().map(ToolSchema::getName).sorted().toList());
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(textResponse(""));
            }
            return scripts.get(i).get();
        }
    }

    @Test
    void runtimeToolScopeUsesSamePrivateToolkitForPresentationAndExecution() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "scoped-1", "tenant_tool", "alpha")),
                                () -> Flux.just(textResponse("scoped-done"))));
        Toolkit platform = new Toolkit();
        AtomicBoolean invoked = new AtomicBoolean();
        MiddlewareBase scopedTools =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        Toolkit privateToolkit = agent.getToolkit().copy();
                        privateToolkit.registerAgentTool(
                                new AlwaysAllowTool("tenant_tool") {
                                    @Override
                                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                                        invoked.set(true);
                                        return super.callAsync(param);
                                    }
                                });
                        RuntimeToolScope.install(
                                ctx,
                                privateToolkit,
                                RuntimeToolScope.hash("tenant-a"),
                                Map.of("test:tenant-tool", "v1"));
                        return next.apply(input);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("scoped")
                        .sysPrompt("test")
                        .model(model)
                        .toolkit(platform)
                        .middleware(scopedTools)
                        .build();

        agent.streamEvents(
                        Msg.builder().role(MsgRole.USER).textContent("use the tenant tool").build(),
                        RuntimeContext.empty())
                .collectList()
                .block();

        assertTrue(invoked.get());
        assertTrue(model.visibleTools.get(0).contains("tenant_tool"));
        assertFalse(agent.getToolkit().getToolNames().contains("tenant_tool"));
    }

    @Test
    void modelStepPinsExecutableHandleUntilNextReasoningRequest() {
        AtomicReference<ReActAgent> owner = new AtomicReference<>();
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        List<StepSnapshot> executionSnapshots = new ArrayList<>();
        AgentTool replacement =
                new AlwaysAllowTool("probe") {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        replacementCalls.incrementAndGet();
                        executionSnapshots.add(param.getRuntimeContext().get(StepSnapshot.class));
                        return super.callAsync(param);
                    }
                };
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AlwaysAllowTool("probe") {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        originalCalls.incrementAndGet();
                        executionSnapshots.add(param.getRuntimeContext().get(StepSnapshot.class));
                        return super.callAsync(param);
                    }
                });
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> {
                                    owner.get().getToolkit().registerAgentTool(replacement);
                                    return Flux.just(toolUseResponse("first", "probe", "alpha"));
                                },
                                () -> Flux.just(toolUseResponse("second", "probe", "beta")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder().name("pinned-step").model(model).toolkit(toolkit).build();
        owner.set(agent);
        var events =
                agent.streamEvents(
                                Msg.builder().role(MsgRole.USER).textContent("probe").build(),
                                RuntimeContext.empty())
                        .collectList()
                        .block();
        assertEquals(1, originalCalls.get());
        assertEquals(1, replacementCalls.get());
        assertEquals(2, executionSnapshots.size());
        assertEquals(1L, executionSnapshots.get(0).sequence());
        assertEquals(2L, executionSnapshots.get(1).sequence());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                executionSnapshots.get(0).toolRegistrationVersion(),
                executionSnapshots.get(1).toolRegistrationVersion());
        var starts =
                events.stream()
                        .filter(ModelCallStartEvent.class::isInstance)
                        .map(ModelCallStartEvent.class::cast)
                        .toList();
        assertEquals(executionSnapshots.get(0), starts.get(0).getStepSnapshot());
        assertEquals(executionSnapshots.get(1), starts.get(1).getStepSnapshot());
        assertEquals(List.of("probe"), starts.get(0).getStepSnapshot().toolNames());
    }

    @Test
    void toolHiddenByFinalModelMiddlewareCannotExecuteInThatStep() {
        AtomicInteger invoked = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AlwaysAllowTool("hidden") {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        invoked.incrementAndGet();
                        return super.callAsync(param);
                    }
                });
        MiddlewareBase hideTool =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onModelCall(
                            Agent agent,
                            RuntimeContext context,
                            io.agentscope.core.middleware.ModelCallInput input,
                            Function<io.agentscope.core.middleware.ModelCallInput, Flux<AgentEvent>>
                                    next) {
                        return next.apply(
                                new io.agentscope.core.middleware.ModelCallInput(
                                        input.messages(),
                                        List.of(),
                                        input.options(),
                                        input.model()));
                    }
                };
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("hidden-call", "hidden", "value")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("hidden-step")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(hideTool)
                        .build();
        agent.streamEvents(
                        Msg.builder().role(MsgRole.USER).textContent("probe").build(),
                        RuntimeContext.empty())
                .collectList()
                .block();
        assertEquals(0, invoked.get());
        assertTrue(model.visibleTools.get(0).isEmpty());
        assertTrue(agent.getToolkit().getToolNames().contains("hidden"));
    }

    @Test
    void rejectedModelStartAtCallBoundaryDoesNotInvokeProvider() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(textResponse("unexpected"))));
        MiddlewareBase reject =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onModelCall(
                            Agent agent,
                            RuntimeContext ctx,
                            io.agentscope.core.middleware.ModelCallInput input,
                            Function<io.agentscope.core.middleware.ModelCallInput, Flux<AgentEvent>>
                                    next) {
                        return next.apply(input)
                                .doOnNext(
                                        event -> {
                                            if (event instanceof ModelCallStartEvent start) {
                                                assertTrue(start.getStepSnapshot() != null);
                                                throw new IllegalStateException("budget rejected");
                                            }
                                        });
                    }
                };
        ReActAgent agent =
                ReActAgent.builder().name("budget-start").model(model).middleware(reject).build();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                        agent.streamEvents(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("test")
                                                .build(),
                                        RuntimeContext.empty())
                                .collectList()
                                .block());
        assertEquals(0, model.calls.get());
    }

    @Test
    void concurrentUsersKeepTheirOwnStepAndToolScope() {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var observed = new java.util.concurrent.ConcurrentHashMap<String, StepSnapshot>();
        Supplier<Flux<ChatResponse>> together =
                () ->
                        Flux.defer(
                                        () -> {
                                            ready.countDown();
                                            try {
                                                if (!ready.await(
                                                        5, java.util.concurrent.TimeUnit.SECONDS)) {
                                                    return Flux.error(
                                                            new IllegalStateException(
                                                                    "model calls did not overlap"));
                                                }
                                            } catch (InterruptedException error) {
                                                Thread.currentThread().interrupt();
                                                return Flux.error(error);
                                            }
                                            return Flux.just(
                                                    toolUseResponse(
                                                            "same-model-call-id",
                                                            "tenant_tool",
                                                            "value"));
                                        })
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                together,
                                together,
                                () -> Flux.just(textResponse("done")),
                                () -> Flux.just(textResponse("done"))));
        MiddlewareBase scopes =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        String owner = ctx.getUserId();
                        Toolkit privateToolkit = new Toolkit();
                        privateToolkit.registerAgentTool(
                                new AlwaysAllowTool("tenant_tool") {
                                    @Override
                                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                                        assertEquals(owner, param.getRuntimeContext().getUserId());
                                        observed.put(
                                                owner,
                                                param.getRuntimeContext().get(StepSnapshot.class));
                                        return super.callAsync(param);
                                    }
                                });
                        RuntimeToolScope.install(
                                ctx, privateToolkit, RuntimeToolScope.hash(owner), Map.of());
                        return next.apply(input);
                    }
                };
        ReActAgent agent =
                ReActAgent.builder().name("shared-agent").model(model).middleware(scopes).build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent("probe").build();
        Flux.merge(
                        agent.streamEvents(
                                input,
                                RuntimeContext.builder().userId("alice").sessionId("a").build()),
                        agent.streamEvents(
                                input,
                                RuntimeContext.builder().userId("bob").sessionId("b").build()))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertEquals(2, observed.size());
        assertEquals(RuntimeToolScope.hash("alice"), observed.get("alice").extensionSetHash());
        assertEquals(RuntimeToolScope.hash("bob"), observed.get("bob").extensionSetHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                observed.get("alice").stepId(), observed.get("bob").stepId());
        assertEquals(1L, observed.get("alice").sequence());
        assertEquals(1L, observed.get("bob").sequence());
        assertTrue(agent.getToolkit().getToolNames().isEmpty());
    }

    @Test
    void finalModelBindingIsUsedForSnapshotAndProviderExecution() {
        var binds = new AtomicInteger();
        ScriptedModel provider = new ScriptedModel(List.of(() -> Flux.just(textResponse("bound"))));
        var router =
                new io.agentscope.core.model.StepBindableModel() {
                    @Override
                    public io.agentscope.core.model.Model bindToStep(
                            RuntimeContext context, List<Msg> messages) {
                        binds.incrementAndGet();
                        return provider;
                    }

                    @Override
                    public String getModelName() {
                        return "mutable-router";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(
                                new IllegalStateException("unbound router must not execute"));
                    }
                };
        var agent = ReActAgent.builder().name("bound-model").model(router).build();
        var events =
                agent.streamEvents(
                                Msg.builder().role(MsgRole.USER).textContent("hello").build(),
                                RuntimeContext.empty())
                        .collectList()
                        .block();
        assertEquals(1, binds.get());
        assertEquals(1, provider.calls.get());
        var start =
                events.stream()
                        .filter(ModelCallStartEvent.class::isInstance)
                        .map(ModelCallStartEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(provider.getModelName(), start.getStepSnapshot().modelName());
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUseResponse(String id, String name, String q) {
        Map<String, Object> in = new HashMap<>();
        in.put("query", q);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder().id(id).name(name).input(in).build()))
                .build();
    }

    private static class AlwaysAllowTool extends ToolBase {
        AlwaysAllowTool(String name) {
            super(name, "always allow", schema(), true, true, false, null, false, false);
        }

        private static Map<String, Object> schema() {
            Map<String, Object> s = new HashMap<>();
            s.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            Map<String, Object> q = new HashMap<>();
            q.put("type", "string");
            props.put("query", q);
            s.put("properties", props);
            return s;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState ctx) {
            return Mono.just(PermissionDecision.allow("ok"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Object q = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text(getName() + ":" + q));
        }
    }

    private static final class RecordingMiddleware implements MiddlewareBase {
        final List<String> trace = new ArrayList<>();

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            trace.add("reply:enter");
            return next.apply(input).doOnComplete(() -> trace.add("reply:exit"));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            trace.add("acting:enter");
            return next.apply(input).doOnComplete(() -> trace.add("acting:exit"));
        }
    }

    @Test
    void twoToolReactLoopProducesOrderedEventsAndFinalText() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("c1", "search", "alpha")),
                                () -> Flux.just(toolUseResponse("c2", "lookup", "beta")),
                                () -> Flux.just(textResponse("done-final"))));
        Toolkit tk = new Toolkit();
        tk.registerAgentTool(new AlwaysAllowTool("search"));
        tk.registerAgentTool(new AlwaysAllowTool("lookup"));

        RecordingMiddleware mw = new RecordingMiddleware();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("you are helpful")
                        .model(model)
                        .toolkit(tk)
                        .middleware(mw)
                        .build();
        AgentState state = agent.getAgentState();

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("find me alpha then beta")
                                                .build()))
                        .collectList()
                        .block();
        assertNotNull(events);

        assertEquals(3, model.calls.get(), "model must be called once per ReAct iteration");

        assertTrue(events.get(0) instanceof AgentStartEvent, "first event must be AgentStartEvent");
        assertTrue(
                events.get(events.size() - 1) instanceof AgentEndEvent,
                "last event must be AgentEndEvent");

        long modelEnds = events.stream().filter(e -> e instanceof ModelCallEndEvent).count();
        assertEquals(3L, modelEnds);

        assertEquals(2L, events.stream().filter(e -> e instanceof ToolCallEndEvent).count());
        long toolResEnds = events.stream().filter(e -> e instanceof ToolResultEndEvent).count();
        assertEquals(2L, toolResEnds);

        events.stream()
                .filter(e -> e instanceof ToolResultEndEvent)
                .forEach(
                        e ->
                                assertEquals(
                                        ToolResultState.SUCCESS,
                                        ((ToolResultEndEvent) e).getState()));

        assertTrue(mw.trace.contains("reply:enter"), mw.trace.toString());
        assertTrue(mw.trace.contains("reply:exit"), mw.trace.toString());
        assertTrue(mw.trace.contains("acting:enter"), mw.trace.toString());
        assertTrue(mw.trace.contains("acting:exit"), mw.trace.toString());

        List<Msg> ctx = state.getContext();
        boolean hasFinalText =
                ctx.stream()
                        .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                        .flatMap(m -> m.getContentBlocks(TextBlock.class).stream())
                        .anyMatch(tb -> tb.getText().equals("done-final"));
        assertTrue(hasFinalText, "final assistant text 'done-final' must be in state.context");
    }

    @Test
    void committedToolResultCreatesContextCheckpointWithMergedRuntimeAttributes() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("checkpoint-call", "search", "q")),
                                () -> Flux.just(textResponse("checkpoint-done"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AlwaysAllowTool("search"));
        AtomicReference<ContextCheckpointStore.Draft> captured = new AtomicReference<>();
        StepSnapshot.Identity identity =
                new StepSnapshot.Identity("run-1", "agent-run-1", "task-1", "attempt-1");
        ContextCheckpointStore store =
                draft -> {
                    captured.set(draft);
                    return new ContextCheckpointStore.StoredCheckpoint(1, draft.historyHash());
                };
        MiddlewareBase checkpointMiddleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        ctx.put(StepSnapshot.Identity.class, identity);
                        ctx.put(
                                ExecutionLeaseSnapshot.class,
                                new ExecutionLeaseSnapshot("worker-1"));
                        ctx.put(ContextCheckpointStore.class, store);
                        return next.apply(input);
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("checkpoint-agent")
                        .model(model)
                        .toolkit(toolkit)
                        .toolExecutionContext(ToolExecutionContext.empty())
                        .middleware(checkpointMiddleware)
                        .build();

        agent.streamEvents(
                        Msg.builder().role(MsgRole.USER).textContent("checkpoint").build(),
                        RuntimeContext.builder().sessionId("checkpoint-session").build())
                .collectList()
                .block();

        ContextCheckpointStore.Draft checkpoint = captured.get();
        assertNotNull(checkpoint);
        assertEquals(identity, checkpoint.identity());
        assertEquals("worker-1", checkpoint.executionLease().owner());
        assertTrue(checkpoint.historyHash().matches("[0-9a-f]{64}"));
        assertTrue(checkpoint.pendingOperationIds().isEmpty());
        assertTrue(
                checkpoint.retainedTail().stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(result -> "checkpoint-call".equals(result.getId())));
    }
}
