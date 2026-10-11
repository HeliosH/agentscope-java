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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the F3-S2 remote projection: out-of-call file IO delegates to a {@link RemoteFilesystem}
 * fallback backed by an {@link InMemoryStore}, in-call uploads dual-write to it, and shell-class
 * operations still require a live sandbox. Also covers the legacy fallback=null path.
 *
 * <p>Uses a real {@link RemoteFilesystem} + {@link InMemoryStore} (no mocking framework needed —
 * harness tests run on JUnit 5 only).
 */
class SandboxBackedFilesystemTest {
    @Test
    void acceptedTransferLimitsAreCallScopedAndNeverChangeTheSharedDefaults() {
        var filesystem = new SandboxBackedFilesystem();
        var small = new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(2, 10, 20);
        var large = new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(4, 100, 200);
        filesystem.configureTransferPolicy(small);
        var oldRun = io.agentscope.core.agent.RuntimeContext.builder().build();
        oldRun.put(io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy.class, large);
        var newRun = io.agentscope.core.agent.RuntimeContext.builder().build();
        assertEquals(large, filesystem.getTransferPolicy(oldRun));
        assertEquals(small, filesystem.getTransferPolicy(newRun));
        assertEquals(small, filesystem.getTransferPolicy());
        assertEquals(large, filesystem.fork().getTransferPolicy(oldRun));
    }

    private static final RuntimeContext RC = RuntimeContext.empty();
    private static final NamespaceFactory NS = rc -> List.of("test", "fs");

    @Test
    void oversizedProjectionFailsClosedAndPreservesPreviousManifest() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        fs.projectSandboxWorkspaceToRemote(
                contextWithSandbox(new FakeSandbox(tarArchive(Map.of("outputs/old.txt", "old")))));
        fs.configureTransferPolicy(
                new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(10, 4, 32));
        var rc =
                contextWithSandbox(
                        new FakeSandbox(tarArchive(Map.of("outputs/new.pptx", "12345"))));
        var receipt = fs.projectSandboxWorkspaceWithReport(rc);
        assertEquals(0, receipt.projectedFiles());
        assertFalse(receipt.completeScan());
        assertEquals(1, receipt.rejectedFiles().size());
        assertThrows(IllegalStateException.class, receipt::verifyComplete);
        assertThrows(IllegalStateException.class, () -> fs.projectSandboxWorkspaceToRemote(rc));
        assertTrue(remote.exists(RC, "/outputs/old.txt"));
        assertTrue(
                remote.read(RC, "/.agentscope/sandbox_projection_manifest", 0, 0)
                        .fileData()
                        .content()
                        .contains("/outputs/old.txt"));
        assertEquals(fs.getTransferPolicy(), fs.fork().getTransferPolicy());
    }

    @Test
    void countAndAggregateByteLimitsReturnIncompleteReceipts() throws Exception {
        for (var policy :
                List.of(
                        new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(1, 10, 20),
                        new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                                10, 4, 4))) {
            var fs = new SandboxBackedFilesystem();
            fs.configureRemoteFallback(new RemoteFilesystem(new InMemoryStore(), NS));
            fs.configureTransferPolicy(policy);
            var report =
                    fs.projectSandboxWorkspaceWithReport(
                            contextWithSandbox(
                                    new FakeSandbox(
                                            tarArchive(
                                                    Map.of(
                                                            "outputs/a.txt",
                                                            "123",
                                                            "outputs/b.txt",
                                                            "456")))));
            assertFalse(report.completeScan());
            assertThrows(IllegalStateException.class, report::verifyComplete);
        }
    }

    @Test
    void outOfCallReadDelegatesToFallbackWhenConfigured() {
        InMemoryStore store = new InMemoryStore();
        // Seed the store with a file via a plain RemoteFilesystem, then read via the sandbox fs.
        RemoteFilesystem seed = new RemoteFilesystem(store, NS);
        seed.write(RC, "/MEMORY.md", "memory content");

        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));

        ReadResult result = fs.read(RC, "/MEMORY.md", 0, 0);
        assertTrue(result.isSuccess());
        assertTrue(result.fileData().content().contains("memory content"));
    }

    @Test
    void linksAndSpecialEntriesRejectTheWholeProjectionBeforePublication() throws Exception {
        for (byte type : new byte[] {'1', '2', '3', '6'}) {
            var bytes = new ByteArrayOutputStream();
            try (var tar = new TarArchiveOutputStream(bytes)) {
                var entry = new TarArchiveEntry("outputs/linked.txt", type);
                entry.setLinkName("/etc/passwd");
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
                tar.finish();
            }
            var remote = new RemoteFilesystem(new InMemoryStore(), NS);
            var fs = new SandboxBackedFilesystem();
            fs.configureRemoteFallback(remote);
            var report =
                    fs.projectSandboxWorkspaceWithReport(
                            contextWithSandbox(new FakeSandbox(bytes.toByteArray())));
            assertFalse(report.completeScan(), "entry type " + (char) type);
            assertEquals(0, report.projectedFiles());
            assertFalse(remote.exists(RC, "/outputs/linked.txt"));
            assertThrows(IllegalStateException.class, report::verifyComplete);
        }
    }

    @Test
    void outOfCallReadThrowsWhenNoFallbackConfigured() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> fs.read(RC, "/MEMORY.md", 0, 0));
    }

    @Test
    void outOfCallExecuteAlwaysThrowsEvenWithFallback() {
        // execute is shell-class: no sandbox means no shell. Fallback does not cover it.
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(new InMemoryStore(), NS));

        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> fs.execute(RC, "ls", null));
    }

    @Test
    void outOfCallWriteDelegatesToFallback() {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));

        fs.write(RC, "/skills/x/SKILL.md", "body");

        // Verify via a plain RemoteFilesystem reading the same store.
        ReadResult readBack = new RemoteFilesystem(store, NS).read(RC, "/skills/x/SKILL.md", 0, 0);
        assertTrue(readBack.isSuccess());
        assertTrue(readBack.fileData().content().contains("body"));
    }

    @Test
    void outOfCallExistsDelegatesToFallback() {
        InMemoryStore store = new InMemoryStore();
        new RemoteFilesystem(store, NS).write(RC, "/MEMORY.md", "x");

        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));

        assertTrue(fs.exists(RC, "/MEMORY.md"));
        assertFalse(fs.exists(RC, "/MISSING.md"));
    }

    @Test
    void outOfCallUploadDelegatesToFallback() {
        // Out-of-call upload (e.g. PUT /skills/workspace between chats) must not throw; it writes
        // the projection so the next in-call read sees the file.
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));

        byte[] content = "skill body".getBytes(StandardCharsets.UTF_8);
        var results = fs.uploadFiles(RC, List.of(Map.entry("/skills/greeter/SKILL.md", content)));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        ReadResult readBack =
                new RemoteFilesystem(store, NS).read(RC, "/skills/greeter/SKILL.md", 0, 0);
        assertTrue(readBack.isSuccess());
        assertTrue(readBack.fileData().content().contains("skill body"));
    }

    @Test
    void inCallUploadDualWritesToFallback() {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));
        RuntimeContext rc = contextWithSandbox(new FakeSandbox());

        byte[] content = "memory".getBytes(StandardCharsets.UTF_8);
        var results = fs.uploadFiles(rc, List.of(Map.entry("/MEMORY.md", content)));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        // The projection is readable via a plain RemoteFilesystem on the same store.
        ReadResult projected = new RemoteFilesystem(store, NS).read(RC, "/MEMORY.md", 0, 0);
        assertTrue(projected.isSuccess());
        assertTrue(projected.fileData().content().contains("memory"));
    }

    @Test
    void inCallExecutePrefersRuntimeContextSandboxOverSharedFallback() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        FakeSandbox scoped = new FakeSandbox("scoped");
        FakeSandbox shared = new FakeSandbox("shared");
        RuntimeContext scopedContext = RuntimeContext.empty();
        scopedContext.put(Sandbox.class, scoped);
        fs.setSandbox(shared);

        var result = fs.execute(scopedContext, "printf marker", null);

        assertEquals("scoped", result.output());
    }

    @Test
    void inCallUploadSucceedsEvenIfProjectionStoreIsBroken() {
        // A store that throws on put; the sandbox write must still succeed.
        RemoteFilesystem brokenFallback = new RemoteFilesystem(new ThrowingStore(), NS);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(brokenFallback);
        RuntimeContext rc = contextWithSandbox(new FakeSandbox());

        var results = fs.uploadFiles(rc, List.of(Map.entry("/MEMORY.md", "x".getBytes())));
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
    }

    @Test
    void releaseProjectionUploadsShellCreatedFilesToFallback() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));
        RuntimeContext rc =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of(
                                                "./generated/report.txt",
                                                "created by shell",
                                                "MEMORY.md",
                                                "shell memory"))));

        int projected = fs.projectSandboxWorkspaceToRemote(rc);

        assertEquals(2, projected);
        fs.setSandbox(null);
        ReadResult report = fs.read(RC, "/generated/report.txt", 0, 0);
        assertTrue(report.isSuccess());
        assertTrue(report.fileData().content().contains("created by shell"));
        ReadResult memory = fs.read(RC, "/MEMORY.md", 0, 0);
        assertTrue(memory.isSuccess());
        assertTrue(memory.fileData().content().contains("shell memory"));
    }

    @Test
    void releaseProjectionReportsUnsafeArchiveEntriesAsIncomplete() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));
        RuntimeContext rc =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of("../escape.txt", "bad", "safe/file.txt", "good"))));

        var receipt = fs.projectSandboxWorkspaceWithReport(rc);
        assertEquals(0, receipt.projectedFiles());
        assertFalse(receipt.completeScan());
        assertThrows(IllegalStateException.class, receipt::verifyComplete);
        fs.setSandbox(null);
        assertFalse(fs.exists(RC, "/escape.txt"));
        ReadResult safe = fs.read(RC, "/safe/file.txt", 0, 0);
        assertFalse(safe.isSuccess());
    }

    @Test
    void releaseProjectionDeletesOnlyStaleManifestEntries() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);

        RuntimeContext first =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of(
                                                "generated/old.txt",
                                                "old",
                                                "generated/keep.txt",
                                                "keep"))));
        assertEquals(2, fs.projectSandboxWorkspaceToRemote(first));
        assertTrue(remote.exists(RC, "/generated/old.txt"));
        assertTrue(remote.exists(RC, "/generated/keep.txt"));

        remote.write(RC, "/sessions/session-1.jsonl", "session mirror");
        RuntimeContext second =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of(
                                                "generated/keep.txt",
                                                "keep v2",
                                                "generated/new.txt",
                                                "new"))));

        assertEquals(2, fs.projectSandboxWorkspaceToRemote(second));

        assertFalse(remote.exists(RC, "/generated/old.txt"));
        assertTrue(remote.exists(RC, "/generated/keep.txt"));
        assertTrue(remote.exists(RC, "/generated/new.txt"));
        assertTrue(remote.exists(RC, "/sessions/session-1.jsonl"));
    }

    @Test
    void releaseProjectionDeletesStaleDirectoryWhenCurrentArchiveHasNoFilesThere()
            throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);

        RuntimeContext first =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(Map.of("generated/old.txt", "old", "MEMORY.md", "m1"))));
        assertEquals(2, fs.projectSandboxWorkspaceToRemote(first));
        assertTrue(remote.exists(RC, "/generated/old.txt"));

        RuntimeContext second =
                contextWithSandbox(new FakeSandbox(tarArchive(Map.of("MEMORY.md", "m2"))));
        assertEquals(1, fs.projectSandboxWorkspaceToRemote(second));

        assertFalse(remote.exists(RC, "/generated/old.txt"));
        ReadResult memory = remote.read(RC, "/MEMORY.md", 0, 0);
        assertTrue(memory.isSuccess());
        assertTrue(memory.fileData().content().contains("m2"));
    }

    @Test
    void hasRemoteFallbackReflectsConfiguration() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        assertFalse(fs.hasRemoteFallback());
        fs.configureRemoteFallback(new RemoteFilesystem(new InMemoryStore(), NS));
        assertTrue(fs.hasRemoteFallback());
    }

    @Test
    void hydratesBrowserUploadsIntoNewSandboxAndExcludesSessionMirrors() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        byte[] upload = new byte[] {0, 1, (byte) 0xff, 2};
        remote.uploadFiles(
                RC,
                List.of(
                        Map.entry("inputs/source.bin", upload),
                        Map.entry(
                                "sessions/session-1.jsonl",
                                "internal".getBytes(StandardCharsets.UTF_8))));
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        FakeSandbox sandbox = new FakeSandbox();

        int count = fs.hydrateRemoteWorkspace(RC, sandbox);

        assertEquals(1, count);
        assertArrayEquals(upload, archivedFile(sandbox.hydratedArchive, "inputs/source.bin"));
        assertEquals(null, archivedFile(sandbox.hydratedArchive, "sessions/session-1.jsonl"));
    }

    @Test
    void persistentWorkspaceHydratesOnlyChangedRemoteVersions() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        remote.write(RC, "/MEMORY.md", "v1");
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        FakeSandbox sandbox = new FakeSandbox(tarArchive(Map.of("MEMORY.md", "v1")), "", true);

        assertEquals(1, fs.hydrateRemoteWorkspace(RC, sandbox));
        assertEquals(0, fs.hydrateRemoteWorkspace(RC, sandbox));

        remote.uploadFiles(
                RC, List.of(Map.entry("/MEMORY.md", "v2".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, fs.hydrateRemoteWorkspace(RC, sandbox));
        assertArrayEquals(
                "v2".getBytes(StandardCharsets.UTF_8),
                archivedFile(sandbox.hydratedArchive, "MEMORY.md"));
    }

    @Test
    void persistentWorkspaceProjectsOnlyChangedContent() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        remote.write(RC, "/MEMORY.md", "same");
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        FakeSandbox sandbox = new FakeSandbox(tarArchive(Map.of("MEMORY.md", "same")), "", true);
        RuntimeContext rc = contextWithSandbox(sandbox);
        assertEquals(1, fs.hydrateRemoteWorkspace(RC, sandbox));

        assertEquals(0, fs.projectSandboxWorkspaceToRemote(rc));
        assertEquals(1L, remote.fileVersions(RC).get("/MEMORY.md"));
    }

    @Test
    void persistentWorkspacePreservesConcurrentRemoteAndSandboxChanges() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        remote.write(RC, "/report.txt", "original");
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        FakeSandbox sandbox =
                new FakeSandbox(tarArchive(Map.of("report.txt", "agent edit")), "", true);
        assertEquals(1, fs.hydrateRemoteWorkspace(RC, sandbox));

        remote.uploadFiles(
                RC,
                List.of(Map.entry("/report.txt", "browser edit".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, fs.projectSandboxWorkspaceToRemote(contextWithSandbox(sandbox)));

        assertEquals("browser edit", remote.read(RC, "/report.txt", 0, 0).fileData().content());
        String conflictPath =
                remote.fileVersions(RC).keySet().stream()
                        .filter(path -> path.startsWith("/generated/conflicts/"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("agent edit", remote.read(RC, conflictPath, 0, 0).fileData().content());
    }

    @Test
    void persistentWorkspaceDoesNotResurrectConcurrentRemoteDelete() throws Exception {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote = new RemoteFilesystem(store, NS);
        remote.write(RC, "/report.txt", "original");
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        FakeSandbox sandbox =
                new FakeSandbox(tarArchive(Map.of("report.txt", "agent edit")), "", true);
        assertEquals(1, fs.hydrateRemoteWorkspace(RC, sandbox));

        assertTrue(remote.delete(RC, "/report.txt").isSuccess());
        assertEquals(1, fs.projectSandboxWorkspaceToRemote(contextWithSandbox(sandbox)));

        assertFalse(remote.exists(RC, "/report.txt"));
        assertTrue(
                remote.fileVersions(RC).keySet().stream()
                        .anyMatch(path -> path.startsWith("/generated/conflicts/")));
    }

    @Test
    void freezesCompleteInventoryBeforeWritesAndReadsTheSandboxArchiveOnlyOnce() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var original = tarArchive(Map.of("outputs/a.txt", "original", "outputs/b.txt", "second"));
        var changed = tarArchive(Map.of("outputs/a.txt", "changed"));
        var archive = new AtomicReference<>(original);
        var reads = new AtomicInteger();
        var sandbox =
                new FakeSandbox(original) {
                    @Override
                    public InputStream persistWorkspace() {
                        reads.incrementAndGet();
                        return new ByteArrayInputStream(archive.get());
                    }
                };
        var admission = new AtomicReference<WorkspaceProjectionPlan>();
        var captured = new AtomicReference<java.nio.file.Path>();
        var completed = new AtomicInteger();
        fs.configureProjectionSink(
                new WorkspaceProjectionSink() {
                    @Override
                    public void onProjectionPlanned(
                            RuntimeContext ctx, WorkspaceProjectionPlan plan) {
                        assertTrue(remote.fileVersions(ctx).isEmpty());
                        assertEquals(2, plan.entries().size());
                        assertEquals(14, plan.totalBytes());
                        assertEquals(64, plan.version().length());
                        admission.set(plan);
                        archive.set(changed);
                    }

                    @Override
                    public void onProjectionCaptured(
                            RuntimeContext ctx,
                            WorkspaceProjectionPlan plan,
                            java.nio.file.Path path)
                            throws Exception {
                        assertTrue(admission.get() == plan);
                        assertTrue(remote.fileVersions(ctx).isEmpty());
                        assertTrue(java.nio.file.Files.size(path) > 0);
                        captured.set(path);
                    }

                    @Override
                    public void onProjectionCompleted(
                            RuntimeContext ctx, WorkspaceProjectionReport report) {
                        completed.incrementAndGet();
                    }
                });
        var report = fs.projectSandboxWorkspaceWithReport(contextWithSandbox(sandbox));
        assertEquals(2, report.projectedFiles());
        assertEquals(1, reads.get());
        assertEquals(1, completed.get());
        assertFalse(java.nio.file.Files.exists(captured.get()));
        assertEquals("original", remote.read(RC, "/outputs/a.txt", 0, 0).fileData().content());
        assertEquals("second", remote.read(RC, "/outputs/b.txt", 0, 0).fileData().content());
        var reverse = new java.util.ArrayList<>(admission.get().entries());
        java.util.Collections.reverse(reverse);
        assertEquals(
                admission.get().version(), new WorkspaceProjectionPlan(reverse, 2, 14).version());
    }

    @Test
    void rejectedAdmissionCannotTransferAnyWorkspaceFile() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        fs.configureProjectionSink(
                new WorkspaceProjectionSink() {
                    @Override
                    public void onProjectionPlanned(
                            RuntimeContext ctx, WorkspaceProjectionPlan plan) {
                        throw new IllegalStateException("manifest database unavailable");
                    }
                });
        assertThrows(
                IllegalStateException.class,
                () ->
                        fs.projectSandboxWorkspaceWithReport(
                                contextWithSandbox(
                                        new FakeSandbox(
                                                tarArchive(Map.of("outputs/a.txt", "abc"))))));
        assertTrue(remote.fileVersions(RC).isEmpty());
    }

    @Test
    void duplicateCanonicalPathsRejectTheEntireScanBeforeAdmission() throws Exception {
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("./outputs/a.txt", "abc");
        files.put("outputs/a.txt", "different");
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var report =
                fs.projectSandboxWorkspaceWithReport(
                        contextWithSandbox(new FakeSandbox(tarArchive(files))));
        assertFalse(report.completeScan());
        assertEquals(0, report.projectedFiles());
        assertTrue(remote.fileVersions(RC).isEmpty());
    }

    @Test
    void unchangedVolumeFilesStillHaveAnAdmittedInventoryAndCatalogCallback() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        remote.write(RC, "/outputs/a.txt", "same");
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var sandbox = new FakeSandbox(tarArchive(Map.of("outputs/a.txt", "same")), "", true);
        fs.hydrateRemoteWorkspace(RC, sandbox);
        var callbacks = new AtomicInteger();
        fs.configureProjectionSink(
                new WorkspaceProjectionSink() {
                    @Override
                    public void onProjectionPlanned(
                            RuntimeContext ctx, WorkspaceProjectionPlan plan) {
                        assertEquals(1, plan.entries().size());
                        assertTrue(plan.entries().get(0).unchanged());
                    }

                    @Override
                    public void onProjectedFile(RuntimeContext ctx, String path, byte[] content) {
                        callbacks.incrementAndGet();
                        assertEquals("/outputs/a.txt", path);
                        assertArrayEquals("same".getBytes(StandardCharsets.UTF_8), content);
                    }
                });
        var report = fs.projectSandboxWorkspaceWithReport(contextWithSandbox(sandbox));
        assertEquals(0, report.projectedFiles());
        assertEquals(1, report.unchangedFiles());
        assertEquals(1, callbacks.get());
        assertEquals(1L, remote.fileVersions(RC).get("/outputs/a.txt"));
    }

    @Test
    void returnedUploadFailureCannotProduceACompletionReceipt() throws Exception {
        var remote =
                new RemoteFilesystem(new InMemoryStore(), NS) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext ctx, List<Map.Entry<String, byte[]>> files) {
                        return files.stream()
                                .map(
                                        e ->
                                                FileUploadResponse.fail(
                                                        e.getKey(), "fixture unavailable"))
                                .toList();
                    }
                };
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var completed = new AtomicInteger();
        fs.configureProjectionSink(
                new WorkspaceProjectionSink() {
                    @Override
                    public void onProjectionCompleted(
                            RuntimeContext ctx, WorkspaceProjectionReport report) {
                        completed.incrementAndGet();
                    }
                });
        assertThrows(
                IllegalStateException.class,
                () ->
                        fs.projectSandboxWorkspaceWithReport(
                                contextWithSandbox(
                                        new FakeSandbox(
                                                tarArchive(Map.of("outputs/a.txt", "abc"))))));
        assertEquals(0, completed.get());
        assertTrue(remote.fileVersions(RC).isEmpty());
    }

    @Test
    void concurrentRemoteChangeToUnchangedSandboxFileRejectsRatherThanClaimsItVerified()
            throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        remote.write(RC, "/outputs/a.txt", "original");
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var sandbox = new FakeSandbox(tarArchive(Map.of("outputs/a.txt", "original")), "", true);
        fs.hydrateRemoteWorkspace(RC, sandbox);
        remote.uploadFiles(
                RC,
                List.of(Map.entry("/outputs/a.txt", "browser".getBytes(StandardCharsets.UTF_8))));
        var report = fs.projectSandboxWorkspaceWithReport(contextWithSandbox(sandbox));
        assertFalse(report.completeScan());
        assertEquals(0, report.projectedFiles());
        assertEquals("browser", remote.read(RC, "/outputs/a.txt", 0, 0).fileData().content());
    }

    private static RuntimeContext contextWithSandbox(Sandbox sandbox) {
        RuntimeContext rc = RuntimeContext.empty();
        rc.put(Sandbox.class, sandbox);
        return rc;
    }

    @Test
    void pinnedToolWritesDoNotBypassReleasePublication() {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        remote.write(RC, "/inputs/pinned.txt", "newer-input");
        remote.write(RC, "/outputs/report.txt", "newer-output");
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var ctx = contextWithSandbox(new FakeSandbox());
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan(
                                "input://fixture",
                                "pinned",
                                List.of(
                                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan
                                                .WorkspaceFile(
                                                "inputs/pinned.txt", new byte[0])))));

        var results =
                fs.uploadFiles(
                        ctx,
                        List.of(
                                Map.entry(
                                        "/inputs/pinned.txt",
                                        "edited".getBytes(StandardCharsets.UTF_8)),
                                Map.entry(
                                        "/outputs/report.txt",
                                        "edited".getBytes(StandardCharsets.UTF_8)),
                                Map.entry(
                                        "/outputs/new.txt",
                                        "new".getBytes(StandardCharsets.UTF_8))));

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(FileUploadResponse::isSuccess));
        assertEquals(
                "newer-input", remote.read(RC, "/inputs/pinned.txt", 0, 0).fileData().content());
        assertEquals(
                "newer-output", remote.read(RC, "/outputs/report.txt", 0, 0).fileData().content());
        assertFalse(remote.exists(RC, "/outputs/new.txt"));
    }

    @Test
    void pinnedProjectionDoesNotRepublishInputsOrDeleteGlobalFiles() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        remote.uploadFiles(
                RC,
                List.of(
                        Map.entry(
                                "/inputs/pinned.txt",
                                "new-upload".getBytes(StandardCharsets.UTF_8)),
                        Map.entry(
                                "/outputs/history.txt", "history".getBytes(StandardCharsets.UTF_8)),
                        Map.entry(
                                "/.agentscope/sandbox_projection_manifest",
                                "/inputs/pinned.txt\n/outputs/history.txt\n"
                                        .getBytes(StandardCharsets.UTF_8))));
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var ctx =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of(
                                                "inputs/pinned.txt",
                                                "old-input",
                                                "outputs/result.txt",
                                                "result"))));
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan(
                                "input://fixture",
                                "pinned",
                                List.of(
                                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan
                                                .WorkspaceFile(
                                                "inputs/pinned.txt",
                                                "old-input".getBytes(StandardCharsets.UTF_8))))));
        assertEquals(1, fs.projectSandboxWorkspaceWithReport(ctx).projectedFiles());
        assertEquals(
                "new-upload", remote.read(RC, "/inputs/pinned.txt", 0, 0).fileData().content());
        assertEquals("history", remote.read(RC, "/outputs/history.txt", 0, 0).fileData().content());
        assertEquals("result", remote.read(RC, "/outputs/result.txt", 0, 0).fileData().content());
    }

    @Test
    void unchangedBoundOutputIsNotRepublishedAndChangedCopyGetsANewTarget() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        remote.uploadFiles(
                RC,
                List.of(
                        Map.entry(
                                "/outputs/report.txt",
                                "newer-upload".getBytes(StandardCharsets.UTF_8))));
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var ctx =
                contextWithSandbox(
                        new FakeSandbox(tarArchive(Map.of("outputs/report.txt", "original"))));
        var input =
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan(
                                "input://fixture",
                                "pinned",
                                List.of(
                                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan
                                                .WorkspaceFile(
                                                "outputs/report.txt",
                                                "original".getBytes(StandardCharsets.UTF_8)))));
        ctx.put(io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class, input);
        assertEquals(0, fs.projectSandboxWorkspaceWithReport(ctx).projectedFiles());
        var changed =
                contextWithSandbox(
                        new FakeSandbox(tarArchive(Map.of("outputs/report.txt", "revision"))));
        changed.put(io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class, input);
        assertEquals(1, fs.projectSandboxWorkspaceWithReport(changed).projectedFiles());
        assertEquals(
                "newer-upload", remote.read(RC, "/outputs/report.txt", 0, 0).fileData().content());
        var revisions = remote.glob(RC, "**", "/outputs/revisions").matches();
        assertEquals(1, revisions.size());
        assertEquals(
                "revision", remote.read(RC, revisions.get(0).path(), 0, 0).fileData().content());
    }

    @Test
    void systemHydrationNeverSearchesUserBodiesAndSharesInputBudget() throws Exception {
        var store =
                new InMemoryStore() {
                    @Override
                    public List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem>
                            search(List<String> ns, int limit, int offset) {
                        throw new AssertionError("Unbounded workspace body search");
                    }

                    @Override
                    public io.agentscope.harness.agent.filesystem.remote.store.StoreItem get(
                            List<String> ns, String key) {
                        if (key.startsWith("/inputs/") || key.startsWith("/outputs/"))
                            throw new AssertionError("Unselected user-file read");
                        return super.get(ns, key);
                    }
                };
        var remote = new RemoteFilesystem(store, NS);
        remote.uploadFiles(
                RC,
                List.of(
                        Map.entry(
                                "/skills/test/SKILL.md", "skill".getBytes(StandardCharsets.UTF_8)),
                        Map.entry(
                                "/outputs/huge.txt", "history".getBytes(StandardCharsets.UTF_8))));
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        var sandbox = new FakeSandbox();
        var ctx = contextWithSandbox(sandbox);
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan(
                                "input://fixture",
                                "pinned",
                                List.of(
                                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan
                                                .WorkspaceFile(
                                                "inputs/pinned.txt", new byte[3])))));
        assertEquals(1, fs.hydrateSystemWorkspace(ctx, sandbox));
        fs.configureTransferPolicy(
                new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(2, 7, 7));
        assertThrows(IllegalStateException.class, () -> fs.hydrateSystemWorkspace(ctx, sandbox));
    }

    @Test
    void inputAndOutputCopiesShareTheSamePhysicalWorkspaceBudget() throws Exception {
        var remote = new RemoteFilesystem(new InMemoryStore(), NS);
        var fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(remote);
        fs.configureTransferPolicy(
                new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(10, 8, 10));
        var context =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of(
                                                "inputs/source.txt",
                                                "12345678",
                                                "outputs/result.txt",
                                                "12345678"))));
        context.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan(
                                "input://budget",
                                "v1",
                                List.of(
                                        new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan
                                                .WorkspaceFile(
                                                "inputs/source.txt",
                                                "12345678".getBytes(StandardCharsets.UTF_8))))));
        var report = fs.projectSandboxWorkspaceWithReport(context);
        assertFalse(report.completeScan());
        assertEquals(0, report.projectedFiles());
        assertThrows(IllegalStateException.class, report::verifyComplete);
        assertFalse(remote.exists(RC, "/outputs/result.txt"));
    }

    /** A minimal Sandbox that reports success for any exec (so uploadFiles proceeds). */
    private static class FakeSandbox implements Sandbox {
        private final byte[] workspaceArchive;
        private final String execOutput;
        private final boolean persistentWorkspace;
        private final StringBuilder manifestBase64 = new StringBuilder();
        private byte[] volumeManifest;
        private byte[] hydratedArchive;

        private FakeSandbox() {
            this(new byte[0], "");
        }

        private FakeSandbox(String execOutput) {
            this(new byte[0], execOutput);
        }

        private FakeSandbox(byte[] workspaceArchive) {
            this(workspaceArchive, "");
        }

        private FakeSandbox(byte[] workspaceArchive, String execOutput) {
            this(workspaceArchive, execOutput, false);
        }

        private FakeSandbox(
                byte[] workspaceArchive, String execOutput, boolean persistentWorkspace) {
            this.workspaceArchive = workspaceArchive != null ? workspaceArchive : new byte[0];
            this.execOutput = execOutput != null ? execOutput : "";
            this.persistentWorkspace = persistentWorkspace;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return new SandboxState() {};
        }

        @Override
        public boolean hasPersistentWorkspace() {
            return persistentWorkspace;
        }

        @Override
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeout) {
            if (persistentWorkspace) {
                if (command.startsWith("if test -f ")) {
                    if (volumeManifest == null) {
                        return new ExecResult(1, "", "", false);
                    }
                    return new ExecResult(
                            0, Base64.getEncoder().encodeToString(volumeManifest), "", false);
                }
                if (command.contains(": > '.agentscope/volume_sync_manifest.json.b64'")) {
                    manifestBase64.setLength(0);
                    return new ExecResult(0, "", "", false);
                }
                if (command.startsWith("printf '%s' '")
                        && command.contains("volume_sync_manifest.json.b64")) {
                    int start = "printf '%s' '".length();
                    int end = command.indexOf("' >>", start);
                    manifestBase64.append(command, start, end);
                    return new ExecResult(0, "", "", false);
                }
                if (command.startsWith("base64 -d ")
                        && command.contains("volume_sync_manifest.json")) {
                    volumeManifest = Base64.getDecoder().decode(manifestBase64.toString());
                    return new ExecResult(0, "", "", false);
                }
            }
            return new ExecResult(0, execOutput, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(workspaceArchive);
        }

        @Override
        public void hydrateWorkspace(InputStream archive) throws Exception {
            hydratedArchive = archive.readAllBytes();
        }
    }

    /** A BaseStore that throws on every write, to verify projection failure is swallowed. */
    private static final class ThrowingStore extends InMemoryStore {
        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            throw new RuntimeException("store down");
        }
    }

    private static byte[] tarArchive(Map<String, String> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return baos.toByteArray();
    }

    private static byte[] archivedFile(byte[] archive, String path) throws Exception {
        if (archive == null) {
            return null;
        }
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (path.equals(entry.getName())) {
                    return tar.readAllBytes();
                }
            }
        }
        return null;
    }
}
