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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryConsolidator} reads daily ledgers and writes watermark / MEMORY.md
 * entirely through {@link io.agentscope.harness.agent.filesystem.AbstractFilesystem}, making it
 * backend-agnostic.
 */
class MemoryConsolidatorFilesystemTest {

    private static void seedStoreFile(
            InMemoryStore store, List<String> ns, String path, String content, Instant modifiedAt) {
        Map<String, Object> value =
                Map.of(
                        "content",
                        content,
                        "encoding",
                        "utf-8",
                        "modified_at",
                        modifiedAt.toString());
        store.put(ns, path, value);
    }

    // ======================================================================
    // readWatermark: returns EPOCH when state file absent
    // ======================================================================

    @Test
    void readWatermark_returnsEpochWhenStateAbsent(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark / writeWatermark round-trip through filesystem
    // ======================================================================

    @Test
    void watermark_roundTripThroughFilesystem(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.parse("2025-06-15T12:00:00Z");
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // readWatermark: no local file is touched — only the filesystem
    // ======================================================================

    @Test
    void watermark_doesNotCreateLocalFile(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

            Instant ts = Instant.now();
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

            // local disk must NOT have the state file — it lives only in the store
            Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
            assertFalse(
                    Files.exists(localState),
                    "state file should not be written to local disk when using RemoteFilesystem");

            // but consolidator reads it correctly from the store
            assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));
        }
    }

    // ======================================================================
    // STATE_FILE constant is preserved
    // ======================================================================

    @Test
    void stateFileRelPath_matchesConstant() {
        assertEquals("memory/" + MemoryConsolidator.STATE_FILE, MemoryConsolidator.STATE_REL_PATH);
    }

    // ======================================================================
    // Local filesystem (no store) — watermark uses local disk via WorkspaceManager
    // ======================================================================

    @Test
    void watermark_localFallback_whenNoFilesystem(@TempDir Path tmp) throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        MemoryConsolidator consolidator = new MemoryConsolidator(wsm, null);

        // No file → EPOCH
        assertEquals(Instant.EPOCH, consolidator.readWatermark(RuntimeContext.empty()));

        // Write via WorkspaceManager (falls to local disk)
        Instant ts = Instant.parse("2025-03-10T09:00:00Z");
        wsm.writeUtf8WorkspaceRelative(
                RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH, ts.toString());

        assertEquals(ts, consolidator.readWatermark(RuntimeContext.empty()));

        // Verify the local file actually exists
        Path localState = tmp.resolve("memory").resolve(MemoryConsolidator.STATE_FILE);
        assertTrue(
                Files.exists(localState),
                "state file should be written to local disk when no filesystem is configured");
    }

    @Test
    void consolidate_emitsSinkEventAfterSuccessfulWrite(@TempDir Path tmp) throws Exception {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        seedStoreFile(
                store,
                ns,
                "memory/2026-06-26.md",
                "- User prefers concise enterprise runtime plans.",
                Instant.parse("2026-06-26T08:00:00Z"));
        seedStoreFile(
                store, ns, "MEMORY.md", "# Old memory\n", Instant.parse("2026-06-26T07:00:00Z"));

        AtomicReference<MemoryConsolidator.ConsolidationEvent> captured = new AtomicReference<>();
        Model model =
                new FixedTextModel("# New memory\n\n- Keep enterprise runtime plans concise.");

        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            MemoryConsolidator consolidator =
                    new MemoryConsolidator(
                            wsm,
                            model,
                            MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT,
                            4000,
                            captured::set);

            consolidator.consolidate(RuntimeContext.builder().sessionId("s1").build()).block();

            MemoryConsolidator.ConsolidationEvent event = captured.get();
            assertEquals("# Old memory\n", event.previousMemory());
            assertTrue(event.dailyEntries().contains("2026-06-26.md"));
            assertEquals(
                    "# New memory\n\n- Keep enterprise runtime plans concise.",
                    event.consolidatedMemory());
            assertEquals(Instant.EPOCH, event.previousWatermark());
            assertEquals("s1", event.runtimeContext().getSessionId());
            assertEquals(
                    "# New memory\n\n- Keep enterprise runtime plans concise.",
                    wsm.readMemoryMd(RuntimeContext.empty()));
            assertTrue(
                    wsm.readManagedWorkspaceFileUtf8(
                                    RuntimeContext.empty(), MemoryConsolidator.STATE_REL_PATH)
                            .contains("T"));
        }
    }

    private record FixedTextModel(String text) implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(text).build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "fixed";
        }
    }
}
