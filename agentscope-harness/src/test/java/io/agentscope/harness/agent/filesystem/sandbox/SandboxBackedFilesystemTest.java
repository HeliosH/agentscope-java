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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
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

    private static final RuntimeContext RC = RuntimeContext.empty();
    private static final NamespaceFactory NS = rc -> List.of("test", "fs");

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
    void releaseProjectionSkipsUnsafeArchiveEntries() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));
        RuntimeContext rc =
                contextWithSandbox(
                        new FakeSandbox(
                                tarArchive(
                                        Map.of("../escape.txt", "bad", "safe/file.txt", "good"))));

        int projected = fs.projectSandboxWorkspaceToRemote(rc);

        assertEquals(1, projected);
        fs.setSandbox(null);
        assertFalse(fs.exists(RC, "/escape.txt"));
        ReadResult safe = fs.read(RC, "/safe/file.txt", 0, 0);
        assertTrue(safe.isSuccess());
        assertTrue(safe.fileData().content().contains("good"));
    }

    @Test
    void hasRemoteFallbackReflectsConfiguration() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        assertFalse(fs.hasRemoteFallback());
        fs.configureRemoteFallback(new RemoteFilesystem(new InMemoryStore(), NS));
        assertTrue(fs.hasRemoteFallback());
    }

    private static RuntimeContext contextWithSandbox(Sandbox sandbox) {
        RuntimeContext rc = RuntimeContext.empty();
        rc.put(Sandbox.class, sandbox);
        return rc;
    }

    /** A minimal Sandbox that reports success for any exec (so uploadFiles proceeds). */
    private static final class FakeSandbox implements Sandbox {
        private final byte[] workspaceArchive;
        private final String execOutput;

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
            this.workspaceArchive = workspaceArchive != null ? workspaceArchive : new byte[0];
            this.execOutput = execOutput != null ? execOutput : "";
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
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeout) {
            return new ExecResult(0, execOutput, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(workspaceArchive);
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
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
}
