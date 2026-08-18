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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLifecycleObserver;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Inject the live session into the {@link SandboxBackedFilesystem} proxy</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Clear the session reference from the filesystem proxy</li>
 * </ol>
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 */
public class SandboxLifecycleMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);
    private static final int RESTORE_MAX_FILES = 5_000;
    private static final long RESTORE_MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final long RESTORE_MAX_TOTAL_BYTES = 64L * 1024L * 1024L;

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private final SandboxLifecycleObserver observer;
    private final AtomicReference<SandboxAcquireResult> legacyAcquireResult =
            new AtomicReference<>();

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this(sandboxManager, filesystemProxy, SandboxLifecycleObserver.noop());
    }

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager,
            SandboxBackedFilesystem filesystemProxy,
            SandboxLifecycleObserver observer) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
        this.observer = observer != null ? observer : SandboxLifecycleObserver.noop();
    }

    /**
     * Acquires the sandbox for the current call. Called from
     * {@code ReActAgent.beforeAgentExecution()} to ensure the sandbox is available
     * for both the {@code call()} and {@code streamEvents()} paths.
     *
     * @param ctx the per-call RuntimeContext (must not be null)
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        long acquireStartNanos = System.nanoTime();
        try {
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            try {
                sandbox.start();
                filesystemProxy.hydrateRemoteWorkspace(ctx, sandbox);
                restoreWorkspace(ctx, sandbox);
                long durationNanos = System.nanoTime() - acquireStartNanos;
                ctx.put(Sandbox.class, sandbox);
                ctx.put(SandboxCallState.class, new SandboxCallState(result));
                filesystemProxy.setSandbox(sandbox);
                legacyAcquireResult.set(result);
                notifyObserver(
                        obs ->
                                obs.onAcquireStartSucceeded(
                                        ctx, result.getAcquisitionSource(), durationNanos));
                log.debug(
                        "[sandbox-mw] Acquired sandbox {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
            } catch (Exception e) {
                clearScopedSandbox(ctx, sandbox, result);
                try {
                    sandboxManager.release(result);
                } catch (Exception releaseErr) {
                    log.warn(
                            "[sandbox-mw] Failed to release session after pre-call failure: {}",
                            releaseErr.getMessage(),
                            releaseErr);
                }
                result.getLease().close();
                throw e;
            }
        } catch (Exception e) {
            notifyObserver(obs -> obs.onAcquireStartFailure(ctx, e));
            log.error("[sandbox-mw] Failed to acquire/start sandbox", e);
            throw new RuntimeException(e);
        }
    }

    private void restoreWorkspace(RuntimeContext ctx, Sandbox sandbox) throws Exception {
        WorkspaceRestorePlan plan = ctx.get(WorkspaceRestorePlan.class);
        if (plan == null || plan.files().isEmpty()) {
            return;
        }
        if (plan.files().size() > RESTORE_MAX_FILES) {
            throw new IllegalStateException(
                    "Workspace checkpoint exceeds restore file limit " + RESTORE_MAX_FILES);
        }
        long totalBytes = 0L;
        ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(archiveBytes)) {
            ArchiveOutputStream<TarArchiveEntry> compatibleArchive = archive;
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (WorkspaceRestorePlan.WorkspaceFile file : plan.files()) {
                if (file.size() > RESTORE_MAX_FILE_BYTES) {
                    throw new IllegalStateException(
                            "Workspace checkpoint file exceeds restore limit: " + file.path());
                }
                totalBytes = Math.addExact(totalBytes, file.size());
                if (totalBytes > RESTORE_MAX_TOTAL_BYTES) {
                    throw new IllegalStateException(
                            "Workspace checkpoint exceeds restore byte limit "
                                    + RESTORE_MAX_TOTAL_BYTES);
                }
                byte[] content = file.content();
                TarArchiveEntry entry = new TarArchiveEntry(file.path());
                entry.setSize(content.length);
                entry.setMode(0644);
                compatibleArchive.putArchiveEntry(entry);
                archive.write(content);
                archive.closeArchiveEntry();
            }
            archive.finish();
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(archiveBytes.toByteArray())) {
            sandbox.hydrateWorkspace(input);
        }
        log.info(
                "[sandbox-mw] Restored workspace checkpoint {} version={} files={} bytes={}",
                plan.checkpointUri(),
                plan.workspaceVersion(),
                plan.files().size(),
                totalBytes);
    }

    /**
     * Releases the sandbox after the current call. Called from
     * {@code ReActAgent.afterAgentExecution()} to ensure cleanup for both paths.
     *
     * @param ctx the per-call RuntimeContext (captured at acquire time)
     */
    public void releaseForCall(RuntimeContext ctx) {
        SandboxCallState callState = ctx != null ? ctx.get(SandboxCallState.class) : null;
        SandboxAcquireResult result =
                callState != null ? callState.result() : legacyAcquireResult.getAndSet(null);
        if (result == null) {
            return;
        }
        Sandbox sandbox = result.getSandbox();
        SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;
        try {
            int projected = filesystemProxy.projectSandboxWorkspaceToRemote(ctx);
            notifyObserver(obs -> obs.onWorkspaceProjectionSucceeded(ctx, projected));
            if (projected > 0) {
                log.debug("[sandbox-mw] Projected {} workspace files before release", projected);
            }
        } catch (Exception e) {
            notifyObserver(obs -> obs.onWorkspaceProjectionFailed(ctx, e));
            log.warn(
                    "[sandbox-mw] Failed to project sandbox workspace to remote fallback: {}",
                    e.getMessage(),
                    e);
        }
        try {
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            notifyObserver(obs -> obs.onStatePersistFailed(ctx, e));
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        try {
            sandboxManager.release(result, ctx);
        } catch (Exception e) {
            notifyObserver(obs -> obs.onBackendReleaseFailed(ctx, e));
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        result.getLease().close();
        clearScopedSandbox(ctx, sandbox, result);
    }

    private void clearScopedSandbox(
            RuntimeContext ctx, Sandbox sandbox, SandboxAcquireResult result) {
        if (ctx != null) {
            ctx.put(Sandbox.class, (Sandbox) null);
            ctx.put(SandboxCallState.class, (SandboxCallState) null);
        }
        if (sandbox != null && filesystemProxy.getSandbox() == sandbox) {
            filesystemProxy.setSandbox(null);
        }
        legacyAcquireResult.compareAndSet(result, null);
    }

    private void notifyObserver(Consumer<SandboxLifecycleObserver> notification) {
        try {
            notification.accept(observer);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Sandbox lifecycle observer failed: {}", e.getMessage(), e);
        }
    }

    private record SandboxCallState(SandboxAcquireResult result) {}
}
