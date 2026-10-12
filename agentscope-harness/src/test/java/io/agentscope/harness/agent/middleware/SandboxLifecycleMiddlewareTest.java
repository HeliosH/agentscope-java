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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ExecutionEnvironmentSnapshot;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult.AcquisitionSource;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxLifecycleObserver;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxLifecycleMiddlewareTest {

    @Test
    void admissionFailurePrecedesProviderAcquisition() {
        var manager = new RecordingSandboxManager(new RecordingSandbox("never-created"));
        var guard =
                org.mockito.Mockito.mock(
                        io.agentscope.harness.agent.sandbox.SandboxUsageGuard.class);
        org.mockito.Mockito.when(
                        guard.reserve(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("quota"));
        var ctx = RuntimeContext.builder().userId("user").build();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        var lifecycle =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem(), null, guard);
        assertThrows(RuntimeException.class, () -> lifecycle.acquireForCall(ctx));
        assertEquals(1, manager.acquisitions.size());
        assertNull(ctx.get(io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding.class));
    }

    @Test
    void activeBorrowerPreventsProjectionAndRootDestruction() {
        var sandbox = new RecordingSandbox("shared");
        var manager = new RecordingSandboxManager(sandbox);
        var guard =
                org.mockito.Mockito.mock(
                        io.agentscope.harness.agent.sandbox.SandboxUsageGuard.class);
        var binding =
                new io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding(
                        "resource", "owner", false);
        org.mockito.Mockito.when(guard.heartbeatSeconds()).thenReturn(30L);
        org.mockito.Mockito.when(
                        guard.reserve(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(binding);
        var ctx = RuntimeContext.builder().userId("user").build();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        var lifecycle =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem(), null, guard);
        lifecycle.acquireForCall(ctx);
        assertSame(
                binding,
                ctx.get(io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("SANDBOX_RESOURCE_USERS_PENDING"))
                .when(guard)
                .prepareCompletion(ctx, binding);
        assertThrows(IllegalStateException.class, () -> lifecycle.observeResult(ctx, null));
        lifecycle.releaseForCall(ctx);
        assertTrue(manager.released.isEmpty());
        assertTrue(sandbox.running);
        assertNull(ctx.get(Sandbox.class));
        org.mockito.Mockito.verify(guard).close(ctx, binding, false);
    }

    @Test
    void failedBorrowedReferenceCloseStillClearsLocalBindingsWithoutDestroyingParent() {
        var sandbox = new RecordingSandbox("parent");
        sandbox.running = true;
        var guard =
                org.mockito.Mockito.mock(
                        io.agentscope.harness.agent.sandbox.SandboxUsageGuard.class);
        org.mockito.Mockito.when(guard.heartbeatSeconds()).thenReturn(30L);
        var parent =
                new io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding(
                        "resource", "parent", false);
        var child =
                new io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding(
                        "resource", "child", true);
        var ctx = RuntimeContext.empty();
        ctx.put(Sandbox.class, sandbox);
        ctx.put(io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding.class, parent);
        org.mockito.Mockito.when(guard.borrow(ctx, parent, sandbox)).thenReturn(child);
        var manager = new RecordingSandboxManager(sandbox);
        var filesystem = new SandboxBackedFilesystem();
        var lifecycle = new SandboxLifecycleMiddleware(manager, filesystem, null, guard);
        lifecycle.acquireForCall(ctx);
        assertSame(
                child,
                ctx.get(io.agentscope.harness.agent.sandbox.SandboxUsageGuard.Binding.class));
        org.mockito.Mockito.when(guard.close(ctx, child, false))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> lifecycle.releaseForCall(ctx));
        assertNull(ctx.get(Sandbox.class));
        assertNull(filesystem.getSandbox());
        assertTrue(sandbox.running);
        assertTrue(manager.released.isEmpty());
    }

    @Test
    void shutdownFailureIsNotConfirmedAsARelease() {
        var sandbox = new RecordingSandbox("failed-release");
        sandbox.failShutdown = true;
        var manager =
                new SandboxManager(
                        new NoopSandboxClient(),
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "test"),
                        "test");
        var result = SandboxAcquireResult.selfManaged(sandbox);
        manager.release(result);
        assertTrue(!result.backendReleaseConfirmed());
        sandbox.failShutdown = false;
        manager.release(result);
        assertTrue(result.backendReleaseConfirmed());
    }

    @Test
    void borrowedChildCannotObserveOrResetItsParentsComputedResult() {
        var sandbox = new RecordingSandbox("parent");
        sandbox.running = true;
        var lifecycle =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(sandbox), new SandboxBackedFilesystem());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var context = RuntimeContext.empty();
        context.put(Sandbox.class, sandbox);
        context.put(
                io.agentscope.harness.agent.sandbox.InvocationResultObserver.class,
                new io.agentscope.harness.agent.sandbox.InvocationResultObserver() {
                    public void onStart(RuntimeContext ctx) {
                        calls.incrementAndGet();
                    }

                    public void onResult(
                            RuntimeContext ctx, io.agentscope.core.message.Msg result) {
                        calls.incrementAndGet();
                    }
                });
        lifecycle.acquireForCall(context);
        lifecycle.observeResult(
                context,
                io.agentscope.core.message.Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .textContent("child")
                        .build());
        lifecycle.releaseForCall(context);
        assertEquals(0, calls.get());
        assertTrue(sandbox.running);
    }

    @Test
    void matchingUnexpiredRetentionSkipsSnapshotAndDestruction() {
        var sandbox = new RecordingSandbox("retained");
        sandbox.running = true;
        var manager =
                new SandboxManager(
                        new NoopSandboxClient(),
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "retention"),
                        "retention");
        var ctx = RuntimeContext.empty();
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold(
                        "retained",
                        java.util.UUID.randomUUID(),
                        java.time.OffsetDateTime.now().plusMinutes(5)));
        manager.release(
                SandboxAcquireResult.selfManaged(
                        sandbox, SandboxLease.noop(), AcquisitionSource.CREATE),
                ctx);
        assertTrue(sandbox.running);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiredOrForeignRetentionCannotSuppressCleanup(boolean expired) {
        var sandbox = new RecordingSandbox("owned");
        sandbox.running = true;
        var manager =
                new SandboxManager(
                        new NoopSandboxClient(),
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "retention"),
                        "retention");
        var ctx = RuntimeContext.empty();
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold(
                        expired ? "owned" : "foreign",
                        java.util.UUID.randomUUID(),
                        expired
                                ? java.time.OffsetDateTime.now().minusSeconds(1)
                                : java.time.OffsetDateTime.now().plusMinutes(5)));
        manager.release(
                SandboxAcquireResult.selfManaged(
                        sandbox, SandboxLease.noop(), AcquisitionSource.CREATE),
                ctx);
        assertTrue(!sandbox.running);
    }

    @Test
    void restoresFilesAboveLegacyFourMiBLimit() throws Exception {
        var sandbox = new RecordingSandbox("restore-large");
        var middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(sandbox), new SandboxBackedFilesystem());
        var context = RuntimeContext.empty();
        context.put(SandboxContext.class, SandboxContext.builder().build());
        byte[] content = new byte[5 * 1024 * 1024];
        content[0] = 42;
        context.put(
                WorkspaceRestorePlan.class,
                new WorkspaceRestorePlan(
                        "workspace-catalog://attempt/previous",
                        "version-1",
                        List.of(new WorkspaceFile("outputs/accepted.pptx", content))));
        middleware.acquireForCall(context);
        assertTrue(sandbox.hydratedArchive.length > content.length);
        middleware.releaseForCall(context);
    }

    @Test
    void rejectsRestoreUsingTheSameConfiguredTransferPolicy() {
        var sandbox = new RecordingSandbox("restore-bounded");
        var fs = new SandboxBackedFilesystem();
        fs.configureTransferPolicy(
                new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(10, 4, 32));
        var middleware = new SandboxLifecycleMiddleware(new RecordingSandboxManager(sandbox), fs);
        var context = RuntimeContext.empty();
        context.put(SandboxContext.class, SandboxContext.builder().build());
        context.put(
                WorkspaceRestorePlan.class,
                new WorkspaceRestorePlan(
                        "workspace-catalog://attempt/previous",
                        "version-1",
                        List.of(new WorkspaceFile("outputs/large.bin", new byte[5]))));
        assertThrows(Exception.class, () -> middleware.acquireForCall(context));
        assertNull(context.get(Sandbox.class));
    }

    @Test
    void hydratesPreparedCheckpointAfterStartAndBeforeCallBecomesVisible() throws Exception {
        RecordingSandbox sandbox = new RecordingSandbox("restore");
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(sandbox), new SandboxBackedFilesystem());
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        ctx.put(
                WorkspaceRestorePlan.class,
                new WorkspaceRestorePlan(
                        "workspace-catalog://attempt/previous",
                        "version-1",
                        List.of(
                                new WorkspaceFile(
                                        "/generated/report.txt",
                                        "restored".getBytes(StandardCharsets.UTF_8)))));

        middleware.acquireForCall(ctx);

        assertSame(sandbox, ctx.get(Sandbox.class));
        assertEquals("restored", restoredFile(sandbox.hydratedArchive, "generated/report.txt"));
        ExecutionEnvironmentSnapshot environment = ctx.get(ExecutionEnvironmentSnapshot.class);
        assertEquals("restore", environment.environmentId());
        assertEquals("version-1", environment.workspaceVersion());
        assertTrue(!environment.version().isBlank());

        middleware.releaseForCall(ctx);
        assertNull(ctx.get(ExecutionEnvironmentSnapshot.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkpointRestorationNeverHydratesTheMutableRemoteWorkspace(boolean empty)
            throws Exception {
        var remote =
                new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
                        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore(),
                        c -> List.of("restore", "remote"));
        var context = RuntimeContext.empty();
        remote.write(context, "/outputs/historical.txt", "old");
        remote.write(context, "/MEMORY.md", "newer mutable context");
        var filesystem = new SandboxBackedFilesystem();
        filesystem.configureRemoteFallback(remote);
        var sandbox = new RecordingSandbox("restore-exact");
        var middleware =
                new SandboxLifecycleMiddleware(new RecordingSandboxManager(sandbox), filesystem);
        context.put(SandboxContext.class, SandboxContext.builder().build());
        context.put(
                WorkspaceRestorePlan.class,
                new WorkspaceRestorePlan(
                        "workspace-catalog://attempt/previous",
                        "verified",
                        empty
                                ? List.of()
                                : List.of(
                                        new WorkspaceFile(
                                                "outputs/current.txt",
                                                "selected".getBytes(StandardCharsets.UTF_8)))));
        middleware.acquireForCall(context);
        if (empty) {
            assertNull(sandbox.hydratedArchive);
        } else {
            assertEquals("selected", restoredFile(sandbox.hydratedArchive, "outputs/current.txt"));
            assertNull(restoredFile(sandbox.hydratedArchive, "outputs/historical.txt"));
            assertNull(restoredFile(sandbox.hydratedArchive, "MEMORY.md"));
        }
        middleware.releaseForCall(context);
    }

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
    void nestedSubagentBorrowsActiveSandboxWithoutAcquiringOrReleasingIt() {
        RecordingSandbox sandbox = new RecordingSandbox("parent");
        sandbox.running = true;
        RecordingSandboxManager manager = new RecordingSandboxManager(new SandboxAcquireResult[0]);
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware middleware = new SandboxLifecycleMiddleware(manager, filesystem);
        RuntimeContext childCtx =
                RuntimeContext.builder()
                        .put(Sandbox.class, sandbox)
                        .put(SandboxContext.class, SandboxContext.builder().build())
                        .build();

        middleware.acquireForCall(childCtx);

        assertSame(sandbox, filesystem.getSandbox());
        assertTrue(manager.acquisitions.isEmpty());

        middleware.releaseForCall(childCtx);

        assertTrue(manager.released.isEmpty());
        assertNull(childCtx.get(Sandbox.class));
        assertNull(filesystem.getSandbox());
        assertTrue(sandbox.isRunning(), "the parent remains the owner of the borrowed sandbox");
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

        assertEquals(
                List.of("acquire_start_succeeded:unknown", "workspace_projection_failed"),
                observer.events);
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
    void observerRecordsAcquireStartSuccessDurationAndSource() {
        RecordingSandbox sandbox = new RecordingSandbox("a");
        RecordingObserver observer = new RecordingObserver();
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(
                                SandboxAcquireResult.selfManaged(
                                        sandbox, SandboxLease.noop(), AcquisitionSource.CREATE)),
                        new SandboxBackedFilesystem(),
                        observer);

        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());

        middleware.acquireForCall(ctx);

        assertEquals(List.of("acquire_start_succeeded:create"), observer.events);
        assertEquals(1, observer.acquireDurations.size());
        assertTrue(observer.acquireDurations.get(0) >= 0);
        assertEquals(List.of(sandbox), observer.acquireSandboxes);
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

    @ParameterizedTest
    @ValueSource(strings = {"projection", "persist", "release"})
    void fatalLifecycleErrorsStillReleaseTheLocalSlotAndClearTheScopedSandbox(String stage) {
        var sandbox = new RecordingSandbox("fatal-cleanup");
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var result =
                SandboxAcquireResult.selfManaged(
                        sandbox, closed::incrementAndGet, AcquisitionSource.CREATE);
        var fatal = new LinkageError("fixture archive ABI failure");
        RecordingSandboxManager manager =
                new RecordingSandboxManager(result) {
                    @Override
                    public void persistState(
                            SandboxAcquireResult r, SandboxContext c, RuntimeContext context) {
                        if (stage.equals("persist")) throw fatal;
                        super.persistState(r, c, context);
                    }

                    @Override
                    public void release(SandboxAcquireResult r, RuntimeContext context) {
                        super.release(r, context);
                        if (stage.equals("release")) throw fatal;
                    }
                };
        var filesystem =
                new SandboxBackedFilesystem() {
                    @Override
                    public io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport
                            projectSandboxWorkspaceWithReport(RuntimeContext c) throws Exception {
                        if (stage.equals("projection")) throw fatal;
                        return super.projectSandboxWorkspaceWithReport(c);
                    }
                };
        var middleware = new SandboxLifecycleMiddleware(manager, filesystem);
        var context = RuntimeContext.empty();
        context.put(SandboxContext.class, SandboxContext.builder().build());
        middleware.acquireForCall(context);
        assertSame(
                fatal, assertThrows(LinkageError.class, () -> middleware.releaseForCall(context)));
        assertEquals(1, closed.get());
        assertEquals(List.of(sandbox), manager.released);
        assertNull(context.get(Sandbox.class));
        assertNull(context.get(ExecutionEnvironmentSnapshot.class));
        assertNull(filesystem.getSandbox());
        middleware.releaseForCall(context);
        assertEquals(1, closed.get());
    }

    private static class RecordingSandboxManager extends SandboxManager {

        private final Queue<SandboxAcquireResult> acquisitions = new ArrayDeque<>();
        private final List<Sandbox> released = new ArrayList<>();

        private RecordingSandboxManager(Sandbox... sandboxes) {
            this(toAcquireResults(sandboxes));
        }

        private RecordingSandboxManager(SandboxAcquireResult... results) {
            super(
                    new NoopSandboxClient(),
                    new SessionSandboxStateStore(new InMemoryAgentStateStore(), "test-agent"),
                    "test-agent");
            acquisitions.addAll(List.of(results));
        }

        private static SandboxAcquireResult[] toAcquireResults(Sandbox... sandboxes) {
            SandboxAcquireResult[] results = new SandboxAcquireResult[sandboxes.length];
            for (int i = 0; i < sandboxes.length; i++) {
                results[i] = SandboxAcquireResult.selfManaged(sandboxes[i]);
            }
            return results;
        }

        @Override
        public SandboxAcquireResult acquire(
                SandboxContext sandboxContext, RuntimeContext runtimeContext) {
            return acquisitions.remove();
        }

        @Override
        public SandboxAcquireResult acquire(
                SandboxContext sandboxContext, RuntimeContext runtimeContext, Runnable admission) {
            admission.run();
            return acquire(sandboxContext, runtimeContext);
        }

        @Override
        public void persistState(
                SandboxAcquireResult result,
                SandboxContext sandboxContext,
                RuntimeContext runtimeContext) {
            // No-op: this test isolates acquire/release ownership.
        }

        @Override
        public void release(SandboxAcquireResult result, RuntimeContext runtimeContext) {
            released.add(result.getSandbox());
        }
    }

    @Test
    void pinnedInputsOverlayCheckpointFilesAndBindWorkspaceVersion() throws Exception {
        var sandbox = new RecordingSandbox("pinned-input");
        var middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(sandbox), new SandboxBackedFilesystem());
        var ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        ctx.put(
                io.agentscope.harness.agent.sandbox.SandboxIsolationOverride.class,
                new io.agentscope.harness.agent.sandbox.SandboxIsolationOverride("run/fixture"));
        ctx.put(
                WorkspaceRestorePlan.class,
                new WorkspaceRestorePlan(
                        "checkpoint://fixture",
                        "checkpoint-version",
                        List.of(
                                new WorkspaceFile(
                                        "outputs/progress.txt",
                                        "progress".getBytes(StandardCharsets.UTF_8)),
                                new WorkspaceFile(
                                        "inputs/pinned.txt",
                                        "wrong".getBytes(StandardCharsets.UTF_8)))));
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "input://fixture",
                                "input-version",
                                List.of(
                                        new WorkspaceFile(
                                                "inputs/pinned.txt",
                                                "original".getBytes(StandardCharsets.UTF_8))))));
        middleware.acquireForCall(ctx);
        assertEquals("original", restoredFile(sandbox.hydratedArchive, "inputs/pinned.txt"));
        assertEquals("progress", restoredFile(sandbox.hydratedArchive, "outputs/progress.txt"));
        middleware.releaseForCall(ctx);
    }

    @Test
    void pinnedInputsWithoutRuntimeIsolationAreRejectedBeforeAcquisition() {
        var middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(new RecordingSandbox("pinned")),
                        new SandboxBackedFilesystem());
        var ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "input://fixture",
                                "input-version",
                                List.of(new WorkspaceFile("inputs/pinned.txt", new byte[0])))));
        assertThrows(IllegalStateException.class, () -> middleware.acquireForCall(ctx));
        assertNull(ctx.get(Sandbox.class));
    }

    @Test
    void emptyInputManifestUsesBoundedSystemHydrationRatherThanWholeUserWorkspace() {
        var sandbox = new RecordingSandbox("empty-workset");
        var filesystem = org.mockito.Mockito.spy(new SandboxBackedFilesystem());
        var middleware =
                new SandboxLifecycleMiddleware(new RecordingSandboxManager(sandbox), filesystem);
        var ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        ctx.put(
                io.agentscope.harness.agent.sandbox.SandboxIsolationOverride.class,
                new io.agentscope.harness.agent.sandbox.SandboxIsolationOverride("run/empty"));
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new WorkspaceRestorePlan("input://empty", "empty-version", List.of())));
        try {
            middleware.acquireForCall(ctx);
            org.mockito.Mockito.verify(filesystem).hydrateSystemWorkspace(ctx, sandbox);
            org.mockito.Mockito.verify(filesystem, org.mockito.Mockito.never())
                    .hydrateRemoteWorkspace(ctx, sandbox);
            assertEquals(
                    "empty-version",
                    ctx.get(
                                    io.agentscope.harness.agent.sandbox.WorkspaceInputPlan
                                            .Materialized.class)
                            .version());
        } catch (Exception error) {
            throw new AssertionError(error);
        } finally {
            middleware.releaseForCall(ctx);
        }
    }

    private static final class FailingProjectionFilesystem extends SandboxBackedFilesystem {

        @Override
        public io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport
                projectSandboxWorkspaceWithReport(RuntimeContext runtimeContext) {
            throw new IllegalStateException("projection down");
        }
    }

    private static final class RecordingObserver implements SandboxLifecycleObserver {

        private final List<String> events = new ArrayList<>();
        private final List<Long> acquireDurations = new ArrayList<>();
        private final List<Sandbox> acquireSandboxes = new ArrayList<>();

        @Override
        public void onAcquireStartFailure(RuntimeContext runtimeContext, Exception error) {
            events.add("acquire_start_failed");
        }

        @Override
        public void onAcquireStartSucceeded(
                RuntimeContext runtimeContext, AcquisitionSource source, long durationNanos) {
            events.add("acquire_start_succeeded:" + source.metricTag());
            acquireDurations.add(durationNanos);
            acquireSandboxes.add(runtimeContext.get(Sandbox.class));
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
        boolean persistent;

        @Override
        public boolean hasPersistentWorkspace() {
            return persistent;
        }

        private final SandboxState state = new SandboxState() {};
        private boolean running;
        private boolean failStart;
        private boolean failStop;
        private boolean failShutdown;
        private byte[] hydratedArchive;

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
        public void hydrateWorkspace(InputStream archive) throws Exception {
            hydratedArchive = archive.readAllBytes();
        }
    }

    private static String restoredFile(byte[] archive, String path) throws Exception {
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            while (tar.getNextTarEntry() != null) {
                if (path.equals(tar.getCurrentEntry().getName())) {
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    tar.transferTo(content);
                    return content.toString(StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void unscopedPersistentVolumeIsRejectedBeforeStart() {
        var sandbox = new RecordingSandbox("volume");
        sandbox.persistent = true;
        var manager = new RecordingSandboxManager(sandbox);
        var middleware = new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());
        var ctx = RuntimeContext.empty();
        ctx.put(SandboxContext.class, SandboxContext.builder().build());
        ctx.put(
                io.agentscope.harness.agent.sandbox.SandboxIsolationOverride.class,
                new io.agentscope.harness.agent.sandbox.SandboxIsolationOverride("run/fixture"));
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "input://fixture",
                                "input-version",
                                List.of(new WorkspaceFile("inputs/pinned.txt", new byte[0])))));
        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(ctx));
        assertTrue(!sandbox.running);
        assertEquals(List.of(sandbox), manager.released);
    }

    @Test
    void borrowedSandboxMustMatchAlreadyMaterializedInputVersion() {
        var sandbox = new RecordingSandbox("parent");
        sandbox.running = true;
        var middleware =
                new SandboxLifecycleMiddleware(
                        new RecordingSandboxManager(new SandboxAcquireResult[0]),
                        new SandboxBackedFilesystem());
        var ctx = RuntimeContext.empty();
        ctx.put(Sandbox.class, sandbox);
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan(
                        new WorkspaceRestorePlan(
                                "input://fixture",
                                "input-version",
                                List.of(new WorkspaceFile("inputs/pinned.txt", new byte[0])))));
        assertThrows(IllegalStateException.class, () -> middleware.acquireForCall(ctx));
        ctx.put(
                io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.Materialized.class,
                new io.agentscope.harness.agent.sandbox.WorkspaceInputPlan.Materialized(
                        sandbox.getState().getSessionId(), "input-version"));
        middleware.acquireForCall(ctx);
        assertTrue(sandbox.running);
        middleware.releaseForCall(ctx);
        assertTrue(sandbox.running);
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
