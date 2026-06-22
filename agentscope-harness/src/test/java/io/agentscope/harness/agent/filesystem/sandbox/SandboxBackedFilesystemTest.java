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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    void inCallUploadDualWritesToFallback() {
        InMemoryStore store = new InMemoryStore();
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(new RemoteFilesystem(store, NS));
        fs.setSandbox(new FakeSandbox());

        byte[] content = "memory".getBytes(StandardCharsets.UTF_8);
        var results = fs.uploadFiles(RC, List.of(Map.entry("/MEMORY.md", content)));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        // The projection is readable via a plain RemoteFilesystem on the same store.
        ReadResult projected = new RemoteFilesystem(store, NS).read(RC, "/MEMORY.md", 0, 0);
        assertTrue(projected.isSuccess());
        assertTrue(projected.fileData().content().contains("memory"));
    }

    @Test
    void inCallUploadSucceedsEvenIfProjectionStoreIsBroken() {
        // A store that throws on put; the sandbox write must still succeed.
        RemoteFilesystem brokenFallback = new RemoteFilesystem(new ThrowingStore(), NS);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.configureRemoteFallback(brokenFallback);
        fs.setSandbox(new FakeSandbox());

        var results = fs.uploadFiles(RC, List.of(Map.entry("/MEMORY.md", "x".getBytes())));
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
    }

    @Test
    void hasRemoteFallbackReflectsConfiguration() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        assertFalse(fs.hasRemoteFallback());
        fs.configureRemoteFallback(new RemoteFilesystem(new InMemoryStore(), NS));
        assertTrue(fs.hasRemoteFallback());
    }

    /** A minimal Sandbox that reports success for any exec (so uploadFiles proceeds). */
    private static final class FakeSandbox implements Sandbox {
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
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
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
}
