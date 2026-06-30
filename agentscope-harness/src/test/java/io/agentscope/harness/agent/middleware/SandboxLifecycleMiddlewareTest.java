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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxLifecycleObserver;
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

    @Test
    void observerRecordsReleaseProjectionFailure() {
        RecordingSandbox sandbox = new RecordingSandbox("a");
        RecordingSandboxManager manager = new RecordingSandboxManager(sandbox);
        RecordingObserver observer = new RecordingObserver();
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(
                        manager, new FailingProjectionFilesystem(), observer);

        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());

        middleware.acquireForCall(ctx);
        middleware.releaseForCall(ctx);

        assertEquals(List.of("workspace_projection_failed"), observer.events);
    }

    @Test
    void observerRecordsAcquireStartFailure() {
        RecordingSandbox sandbox = new RecordingSandbox("a");
        sandbox.failStart = true;
        RecordingObserver observer = new RecordingObserver();
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(sandbox),
                        new SandboxBackedFilesystem(),
                        observer);

        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());

        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(ctx));

        assertEquals(List.of("acquire_start_failed"), observer.events);
    }

    @Test
    void managerObserverRecordsPersistAndStopFailuresSwallowedDuringCleanup() {
        RecordingObserver observer = new RecordingObserver();
        SandboxManager manager =
                new SandboxManager(
                        new FailingSerializeSandboxClient(),
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "test-agent"),
                        "test-agent",
                        SandboxExecutionGuard.noop(),
                        observer);
        RecordingSandbox sandbox = new RecordingSandbox("a");
        sandbox.running = true;
        sandbox.failStop = true;
        sandbox.failShutdown = true;
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("user-1")
                        .put(
                                SandboxContext.class,
                                SandboxContext.builder()
                                        .isolationScope(IsolationScope.USER)
                                        .build())
                        .build();

        manager.persistState(
                SandboxAcquireResult.selfManaged(sandbox), ctx.get(SandboxContext.class), ctx);
        manager.release(SandboxAcquireResult.selfManaged(sandbox), ctx);

        assertEquals(
                List.of("state_persist_failed", "sandbox_stop_failed", "sandbox_shutdown_failed"),
                observer.events);
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

    private static final class FailingProjectionFilesystem extends SandboxBackedFilesystem {

        @Override
        public int projectSandboxWorkspaceToRemote(RuntimeContext runtimeContext) {
            throw new IllegalStateException("projection down");
        }
    }

    private static final class RecordingObserver implements SandboxLifecycleObserver {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onAcquireStartFailure(RuntimeContext runtimeContext, Exception error) {
            events.add("acquire_start_failed");
        }

        @Override
        public void onWorkspaceProjectionFailed(RuntimeContext runtimeContext, Exception error) {
            events.add("workspace_projection_failed");
        }

        @Override
        public void onStatePersistFailed(RuntimeContext runtimeContext, Exception error) {
            events.add("state_persist_failed");
        }

        @Override
        public void onSandboxStopFailed(RuntimeContext runtimeContext, Exception error) {
            events.add("sandbox_stop_failed");
        }

        @Override
        public void onSandboxShutdownFailed(RuntimeContext runtimeContext, Exception error) {
            events.add("sandbox_shutdown_failed");
        }
    }

    private static final class RecordingSandbox implements Sandbox {

        private final SandboxState state = new SandboxState() {};
        private boolean running;
        private boolean failStart;
        private boolean failStop;
        private boolean failShutdown;

        private RecordingSandbox(String sessionId) {
            state.setSessionId(sessionId);
        }

        @Override
        public void start() {
            if (failStart) {
                throw new IllegalStateException("start down");
            }
            running = true;
        }

        @Override
        public void stop() {
            if (failStop) {
                throw new IllegalStateException("stop down");
            }
            running = false;
        }

        @Override
        public void shutdown() {
            if (failShutdown) {
                throw new IllegalStateException("shutdown down");
            }
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

    private static class NoopSandboxClient implements SandboxClient<SandboxClientOptions> {

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

    private static final class FailingSerializeSandboxClient extends NoopSandboxClient {

        @Override
        public String serializeState(SandboxState state) {
            throw new IllegalStateException("state store down");
        }
    }
}
