/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolExecutionJournalTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void persistsPreparedAndRunningBeforeToolThenTerminalResult() {
        var events = new ArrayList<String>();
        var journal = new RecordingJournal(events, ToolExecutionJournal.PrepareResult.execute());
        var calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new TestTool(calls, events, false, ToolRetrySafety.NEVER));

        ToolResultBlock result = call(toolkit, journal);

        assertEquals(List.of("prepare", "running", "tool", "SUCCEEDED"), events);
        assertEquals("ok", text(result));
        assertNotNull(journal.invocation);
        assertEquals("call-1", journal.invocation.toolCallId());
        assertEquals("journal_tool", journal.invocation.toolName());
        assertEquals(Map.of("value", "alpha"), journal.invocation.input());
        assertTrue(!journal.invocation.inputHash().isBlank());
    }

    @Test
    void usesRuntimeIdentityWhenResumingToolWithoutModelStepSnapshot() {
        var events = new ArrayList<String>();
        var journal = new RecordingJournal(events, ToolExecutionJournal.PrepareResult.execute());
        var calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new TestTool(calls, events, false, ToolRetrySafety.NEVER));
        StepSnapshot.Identity identity =
                new StepSnapshot.Identity("run-1", "agent-run-1", "task-1", "attempt-1");
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .put(StepSnapshot.Identity.class, identity)
                        .put(ToolExecutionJournal.class, journal)
                        .build();

        ToolResultBlock result = call(toolkit, context);

        assertEquals("ok", text(result));
        assertEquals(identity, journal.invocation.identity());
        assertEquals("run-1:call-1", journal.invocation.operationId());
    }

    @Test
    void reusesCommittedResultWithoutInvokingTool() {
        var events = new ArrayList<String>();
        var journal =
                new RecordingJournal(
                        events,
                        ToolExecutionJournal.PrepareResult.reuse(ToolResultBlock.text("prior")));
        var calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new TestTool(calls, events, false, ToolRetrySafety.NEVER));

        ToolResultBlock result = call(toolkit, journal);

        assertEquals("prior", text(result));
        assertEquals(0, calls.get());
        assertEquals(List.of("prepare"), events);
    }

    @Test
    void reconciliationStatePreventsBlindReplay() {
        var events = new ArrayList<String>();
        var journal =
                new RecordingJournal(
                        events,
                        ToolExecutionJournal.PrepareResult.reconcile("verify external outcome"));
        var calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new TestTool(calls, events, false, ToolRetrySafety.NEVER));

        ToolResultBlock result = call(toolkit, journal);

        assertEquals(0, calls.get());
        assertEquals(List.of("prepare"), events);
        assertTrue(text(result).contains("verify external outcome"));
    }

    @Test
    void unknownSideEffectErrorIsRecordedAsOutcomeUnknown() {
        var events = new ArrayList<String>();
        var journal = new RecordingJournal(events, ToolExecutionJournal.PrepareResult.execute());
        var calls = new AtomicInteger();
        Toolkit toolkit = toolkit(new TestTool(calls, events, true, ToolRetrySafety.NEVER));

        ToolResultBlock result = call(toolkit, journal);

        assertEquals(1, calls.get());
        assertTrue(text(result).contains("response lost"));
        assertEquals(List.of("prepare", "running", "tool", "OUTCOME_UNKNOWN"), events);
    }

    private static Toolkit toolkit(AgentTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static ToolResultBlock call(Toolkit toolkit, ToolExecutionJournal journal) {
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .put(ToolExecutionJournal.class, journal)
                        .build();
        return call(toolkit, context);
    }

    private static ToolResultBlock call(Toolkit toolkit, RuntimeContext context) {
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("journal_tool")
                        .input(Map.of("value", "alpha"))
                        .content("{\"value\":\"alpha\"}")
                        .build();
        return toolkit.callTools(List.of(use), null, null, context).block(TIMEOUT).get(0);
    }

    private static String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    private static final class TestTool implements AgentTool {
        private final AtomicInteger calls;
        private final List<String> events;
        private final boolean fail;
        private final ToolRetrySafety retrySafety;

        private TestTool(
                AtomicInteger calls,
                List<String> events,
                boolean fail,
                ToolRetrySafety retrySafety) {
            this.calls = calls;
            this.events = events;
            this.fail = fail;
            this.retrySafety = retrySafety;
        }

        @Override
        public String getName() {
            return "journal_tool";
        }

        @Override
        public String getDescription() {
            return "journal test tool";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object", "properties", Map.of("value", Map.of("type", "string")));
        }

        @Override
        public ToolRetrySafety getRetrySafety() {
            return retrySafety;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            events.add("tool");
            return fail
                    ? Mono.error(new IllegalStateException("external success, response lost"))
                    : Mono.just(ToolResultBlock.text("ok"));
        }
    }

    private static final class RecordingJournal implements ToolExecutionJournal {
        private final List<String> events;
        private final PrepareResult prepared;
        private Invocation invocation;

        private RecordingJournal(List<String> events, PrepareResult prepared) {
            this.events = events;
            this.prepared = prepared;
        }

        @Override
        public PrepareResult prepare(Invocation invocation) {
            this.invocation = invocation;
            events.add("prepare");
            return prepared;
        }

        @Override
        public void markRunning(Invocation invocation) {
            events.add("running");
        }

        @Override
        public void complete(
                Invocation invocation,
                TerminalStatus status,
                ToolResultBlock result,
                Throwable error) {
            events.add(status.name());
        }
    }
}
