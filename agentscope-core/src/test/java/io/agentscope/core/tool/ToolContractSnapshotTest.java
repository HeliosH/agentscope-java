/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolContractSnapshotTest {
    @Test
    void capturesImmutableCanonicalSchemaAndExplicitVersion() {
        var tool = new VersionedTool();
        var first = ToolContractSnapshot.capture(tool, Map.of());
        var codec = JsonUtils.getJsonCodec();
        assertEquals(first, codec.fromJson(codec.toJson(first), ToolContractSnapshot.class));
        assertEquals("contract_tool", first.toolName());
        assertTrue(first.recoverable());
        tool.schema.put("description", "new shape");
        assertNotEquals(first.hash(), ToolContractSnapshot.capture(tool, Map.of()).hash());
        assertFalse(first.descriptorJson().contains("new shape"));
        tool.schema.remove("description");
        tool.version = "2";
        assertNotEquals(first.hash(), ToolContractSnapshot.capture(tool, Map.of()).hash());
    }

    @Test
    void presetValuesAreBoundAsDigestsNotPlaintext() {
        var tool = new VersionedTool();
        var first = ToolContractSnapshot.capture(tool, Map.of("hidden", "test-private-fixture"));
        assertFalse(first.descriptorJson().contains("test-private-fixture"));
        assertNotEquals(
                first.hash(),
                ToolContractSnapshot.capture(tool, Map.of("hidden", "changed")).hash());
    }

    @Test
    void opaqueToolsCannotClaimAnImplementationIdentity() {
        var tool = new VersionedTool();
        tool.version = null;
        var opaque = ToolContractSnapshot.capture(tool, Map.of());
        assertFalse(opaque.recoverable());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolContractSnapshot(1, opaque.descriptorJson(), opaque.hash(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolContractSnapshot(1, opaque.descriptorJson(), "f".repeat(64), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolContractSnapshot(2, opaque.descriptorJson(), opaque.hash(), false));
    }

    @Test
    void rejectsOversizedCyclicAndLiveObjectDescriptorsBeforeSerialization() {
        var tool = new VersionedTool();
        tool.description = "x".repeat(262145);
        assertThrows(
                IllegalArgumentException.class, () -> ToolContractSnapshot.capture(tool, Map.of()));
        tool.description = "fixture";
        tool.schema.put("cycle", tool.schema);
        assertThrows(
                IllegalArgumentException.class, () -> ToolContractSnapshot.capture(tool, Map.of()));
        tool.schema.remove("cycle");
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContractSnapshot.capture(tool, Map.of("client", new Object())));
    }

    @Test
    void callerAndOriginKeysDistinguishChildrenAndRepeatedProviderIdsButNotAttempts() {
        var root = new StepSnapshot.Identity("run", "root", "task", "a1");
        var nextAttempt = new StepSnapshot.Identity("run", "root", "task", "a2");
        var child = new StepSnapshot.Identity("run", "child", "child-task", "c1");
        String key = ToolOperationKey.of("run", root, "message-1", "call-0");
        assertEquals(key, ToolOperationKey.of("run", nextAttempt, "message-1", "call-0"));
        assertNotEquals(key, ToolOperationKey.of("run", child, "message-1", "call-0"));
        assertNotEquals(key, ToolOperationKey.of("run", root, "message-2", "call-0"));
    }

    @Test
    void blocksChangedContractBeforeAnyUnjournaledExecution() {
        var tool = new VersionedTool();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        var pinned = StepToolContracts.capture(toolkit);
        var context = RuntimeContext.builder().put(StepToolContracts.class, pinned).build();
        tool.version = "2";
        var use =
                ToolUseBlock.builder()
                        .id("call-0")
                        .name(tool.getName())
                        .input(Map.of())
                        .content("{}")
                        .build();
        var result =
                toolkit.callTools(List.of(use), null, null, context)
                        .block(Duration.ofSeconds(3))
                        .get(0);
        assertTrue(
                ((TextBlock) result.getOutput().get(0))
                        .getText()
                        .contains("Tool contract changed"));
        assertEquals(0, tool.calls.get());
    }

    @Test
    void localClassVersionIsStableAndUnavailableClassesAreOpaque() {
        assertEquals(
                ToolContractSnapshot.implementationVersion(VersionedTool.class),
                ToolContractSnapshot.implementationVersion(VersionedTool.class));
        assertNotNull(ToolContractSnapshot.implementationVersion(VersionedTool.class));
        assertNull(ToolContractSnapshot.implementationVersion(int.class));
    }

    @Test
    void rechecksMutableImplementationAfterJournalPreparationBeforeExternalDispatch() {
        var tool = new VersionedTool();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        var outcome =
                new java.util.concurrent.atomic.AtomicReference<
                        ToolExecutionJournal.TerminalStatus>();
        ToolExecutionJournal journal =
                new ToolExecutionJournal() {
                    public PrepareResult prepare(Invocation invocation) {
                        tool.version = "2";
                        return PrepareResult.execute();
                    }

                    public void markRunning(Invocation invocation) {}

                    public void complete(
                            Invocation invocation,
                            TerminalStatus status,
                            ToolResultBlock result,
                            Throwable error) {
                        outcome.set(status);
                    }
                };
        var context = RuntimeContext.builder().put(ToolExecutionJournal.class, journal).build();
        var use =
                ToolUseBlock.builder()
                        .id("call-0")
                        .name(tool.getName())
                        .input(Map.of())
                        .content("{}")
                        .build();
        var result =
                toolkit.callTools(List.of(use), null, null, context)
                        .block(Duration.ofSeconds(3))
                        .get(0);
        assertTrue(
                ((TextBlock) result.getOutput().get(0))
                        .getText()
                        .contains("before external dispatch"));
        assertEquals(0, tool.calls.get());
        assertEquals(ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN, outcome.get());
    }

    static class VersionedTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, Object> schema = new HashMap<>(Map.of("type", "object"));
        String version = "1";
        String description = "fixture";

        public String getName() {
            return "contract_tool";
        }

        public String getContractVersion() {
            return version;
        }

        public String getDescription() {
            return description;
        }

        public Map<String, Object> getParameters() {
            return schema;
        }

        public Mono<ToolResultBlock> callAsync(ToolCallParam input) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("done"));
        }
    }
}
