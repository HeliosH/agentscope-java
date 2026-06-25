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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class SandboxLifecycleMiddlewareTest {

    @Test
    void releaseUsesCallScopedAcquireResultUnderInterleavedCalls() {
        RecordingSandbox sandboxA = new RecordingSandbox("a");
        RecordingSandbox sandboxB = new RecordingSandbox("b");
        RecordingSandboxManager manager = new RecordingSandboxManager(sandboxA, sandboxB);
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware middleware = new SandboxLifecycleMiddleware(manager, filesystem);

        RuntimeContext ctxA = RuntimeContext.empty();
        RuntimeContext ctxB = RuntimeContext.empty();
        ctxA.put(SandboxContext.class, SandboxContext.builder().build());
        ctxB.put(SandboxContext.class, SandboxContext.builder().build());

        middleware.acquireForCall(ctxA);
        middleware.acquireForCall(ctxB);

        assertSame(sandboxA, ctxA.get(Sandbox.class));
        assertSame(sandboxB, ctxB.get(Sandbox.class));

        middleware.releaseForCall(ctxA);

        assertEquals(List.of(sandboxA), manager.released);
        assertNull(ctxA.get(Sandbox.class));
        assertSame(sandboxB, filesystem.getSandbox(), "Releasing A must not clear B");

        middleware.releaseForCall(ctxB);

        assertEquals(List.of(sandboxA, sandboxB), manager.released);
        assertNull(ctxB.get(Sandbox.class));
        assertNull(filesystem.getSandbox());
    }

    private static final class RecordingSandboxManager extends SandboxManager {

        private final Queue<SandboxAcquireResult> acquisitions = new ArrayDeque<>();
        private final List<Sandbox> released = new ArrayList<>();

        private RecordingSandboxManager(Sandbox... sandboxes) {
            super(
                    new NoopSandboxClient(),
                    new SessionSandboxStateStore(new InMemoryAgentStateStore(), "test-agent"),
                    "test-agent");
            for (Sandbox sandbox : sandboxes) {
                acquisitions.add(SandboxAcquireResult.selfManaged(sandbox));
            }
        }

        @Override
        public SandboxAcquireResult acquire(
                SandboxContext sandboxContext, RuntimeContext runtimeContext) {
            return acquisitions.remove();
        }

        @Override
        public void persistState(
                SandboxAcquireResult result,
                SandboxContext sandboxContext,
                RuntimeContext runtimeContext) {
            // No-op: this test isolates acquire/release ownership.
        }

        @Override
        public void release(SandboxAcquireResult result) {
            released.add(result.getSandbox());
        }
    }

    private static final class RecordingSandbox implements Sandbox {

        private final SandboxState state = new SandboxState() {};
        private boolean running;

        private RecordingSandbox(String sessionId) {
            state.setSessionId(sessionId);
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void close() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeout) {
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    private static final class NoopSandboxClient implements SandboxClient<SandboxClientOptions> {

        @Override
        public Sandbox create(
                WorkspaceSpec workspaceSpec,
                SandboxSnapshotSpec snapshotSpec,
                SandboxClientOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Sandbox resume(SandboxState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Sandbox sandbox) {}

        @Override
        public String serializeState(SandboxState state) {
            return "{}";
        }

        @Override
        public SandboxState deserializeState(String json) {
            throw new UnsupportedOperationException();
        }
    }
}
