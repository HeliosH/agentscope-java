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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSandboxFilesystem} that delegates execution to a live {@link Sandbox}.
 *
 * <p>Stable proxy created at agent build time. A fresh {@link Sandbox} is attached to the
 * per-call {@link RuntimeContext} by {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}; the volatile {@code sandbox}
 * field remains as a legacy fallback for callers that do not pass the scoped context through.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);
    private static final int REMOTE_PROJECTION_BATCH_SIZE = 100;
    private static final int REMOTE_PROJECTION_MAX_FILES = 5_000;
    private static final long REMOTE_PROJECTION_MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long REMOTE_PROJECTION_MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final String REMOTE_PROJECTION_MANIFEST =
            "/.agentscope/sandbox_projection_manifest";
    private static final String VOLUME_SYNC_MANIFEST = "/.agentscope/volume_sync_manifest.json";
    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, VolumeSyncEntry>> SYNC_MANIFEST_TYPE =
            new TypeReference<>() {};

    private final String fsId;
    private volatile Sandbox sandbox;

    /**
     * Optional remote projection target. When set, file content IO (read/write/edit/exists/ls/
     * upload/download) performed <em>outside a call</em> (sandbox == null) delegates to this remote
     * filesystem backed by a {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore},
     * so MEMORY.md/skills/etc. remain readable and writable between calls. Inside a call, writes are
     * dual-written here (best-effort) so the projection stays current.
     *
     * <p>This is the F3-S2 fix for the "No active sandbox" gap. Shell-class operations (execute,
     * grep, glob, delete, move) still require a live sandbox and throw outside a call — that is the
     * correct semantics (no sandbox to run them in).
     */
    private volatile RemoteFilesystem remoteFallback;

    private volatile WorkspaceProjectionSink projectionSink = WorkspaceProjectionSink.noop();

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates an unbound proxy with the same durable projection configuration.
     *
     * <p>Subagents need their own proxy because their calls can overlap, but they must still use
     * the deployment-selected sandbox backend and the same tenant projection store. The live
     * {@link Sandbox} remains call-scoped and is deliberately not copied.
     */
    public SandboxBackedFilesystem fork() {
        SandboxBackedFilesystem child = new SandboxBackedFilesystem();
        child.remoteFallback = this.remoteFallback;
        child.projectionSink = this.projectionSink;
        return child;
    }

    /**
     * Wires the remote projection backend. Must be called once at agent build time, before any
     * call. Passing {@code null} disables projection (the legacy behaviour: all out-of-call IO
     * throws).
     */
    public void configureRemoteFallback(RemoteFilesystem fallback) {
        this.remoteFallback = fallback;
    }

    /**
     * Wires an optional observer for release-time workspace projection. The sink is invoked only
     * after the remote projection write/delete has succeeded.
     */
    public void configureProjectionSink(WorkspaceProjectionSink sink) {
        this.projectionSink = sink != null ? sink : WorkspaceProjectionSink.noop();
    }

    /** Returns whether a remote projection backend is configured. */
    public boolean hasRemoteFallback() {
        return remoteFallback != null;
    }

    /**
     * Restores the durable remote workspace into a newly acquired sandbox.
     *
     * <p>Browser uploads happen between agent calls, when no sandbox is active, and therefore land
     * in the remote fallback. Hydrating that projection here makes those files available to shell
     * and filesystem tools in the next call. Session mirrors are runtime bookkeeping and are never
     * exposed inside the execution workspace.
     */
    public int hydrateRemoteWorkspace(RuntimeContext runtimeContext, Sandbox target)
            throws Exception {
        RemoteFilesystem fallback = remoteFallback;
        if (fallback == null || target == null) {
            return 0;
        }
        boolean incremental = target.hasPersistentWorkspace();
        Map<String, Long> remoteVersions =
                incremental ? normalizedRemoteVersions(runtimeContext, fallback) : Map.of();
        Map<String, VolumeSyncEntry> previous =
                incremental ? readVolumeSyncManifest(runtimeContext, target) : Map.of();
        GlobResult glob = fallback.glob(runtimeContext, "**", "/");
        if (!glob.isSuccess() || glob.matches() == null) {
            return 0;
        }

        List<String> allPaths =
                glob.matches().stream()
                        .filter(file -> !file.isDirectory())
                        .map(FileInfo::path)
                        .filter(path -> normalizeHydrationPath(path) != null)
                        .limit(REMOTE_PROJECTION_MAX_FILES + 1L)
                        .toList();
        if (allPaths.size() > REMOTE_PROJECTION_MAX_FILES) {
            throw new IllegalStateException(
                    "Remote workspace exceeds hydration file limit " + REMOTE_PROJECTION_MAX_FILES);
        }
        List<String> paths =
                incremental
                        ? allPaths.stream()
                                .filter(
                                        path -> {
                                            String normalized = normalizeHydrationPath(path);
                                            VolumeSyncEntry entry = previous.get(normalized);
                                            long version =
                                                    remoteVersions.getOrDefault(normalized, 0L);
                                            return entry == null
                                                    || version == 0L
                                                    || entry.remoteVersion() != version;
                                        })
                                .toList()
                        : allPaths;

        if (incremental) {
            removeRemoteDeletedVolumeFiles(runtimeContext, target, previous, remoteVersions);
        }

        ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
        long totalBytes = 0L;
        int hydrated = 0;
        Map<String, VolumeSyncEntry> next =
                incremental ? new LinkedHashMap<>(previous) : new LinkedHashMap<>();
        if (incremental) {
            next.keySet().retainAll(remoteVersions.keySet());
        }
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(archiveBytes)) {
            ArchiveOutputStream<TarArchiveEntry> compatibleArchive = archive;
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (int offset = 0; offset < paths.size(); offset += REMOTE_PROJECTION_BATCH_SIZE) {
                List<String> batch =
                        paths.subList(
                                offset,
                                Math.min(paths.size(), offset + REMOTE_PROJECTION_BATCH_SIZE));
                for (FileDownloadResponse file : fallback.downloadFiles(runtimeContext, batch)) {
                    if (!file.isSuccess() || file.content() == null) {
                        log.warn(
                                "[sandbox-fs] Skipping remote workspace file {} during hydration:"
                                        + " {}",
                                file.path(),
                                file.error());
                        continue;
                    }
                    byte[] content = file.content();
                    if (content.length > REMOTE_PROJECTION_MAX_FILE_BYTES) {
                        throw new IllegalStateException(
                                "Remote workspace file exceeds hydration limit: " + file.path());
                    }
                    totalBytes = Math.addExact(totalBytes, content.length);
                    if (totalBytes > REMOTE_PROJECTION_MAX_TOTAL_BYTES) {
                        throw new IllegalStateException(
                                "Remote workspace exceeds hydration byte limit "
                                        + REMOTE_PROJECTION_MAX_TOTAL_BYTES);
                    }
                    String path = normalizeHydrationPath(file.path());
                    if (path == null) {
                        continue;
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(path.substring(1));
                    entry.setSize(content.length);
                    entry.setMode(0644);
                    compatibleArchive.putArchiveEntry(entry);
                    archive.write(content);
                    archive.closeArchiveEntry();
                    hydrated++;
                    if (incremental) {
                        next.put(
                                path,
                                new VolumeSyncEntry(
                                        remoteVersions.getOrDefault(path, 0L), sha256(content)));
                    }
                }
            }
            archive.finish();
        }
        if (hydrated > 0) {
            try (InputStream input = new java.io.ByteArrayInputStream(archiveBytes.toByteArray())) {
                target.hydrateWorkspace(input);
            }
            log.debug(
                    "[sandbox-fs] Hydrated {} remote workspace files ({} bytes)",
                    hydrated,
                    totalBytes);
        }
        if (incremental) {
            writeVolumeSyncManifest(runtimeContext, target, next);
            log.info(
                    "[sandbox-fs] Volume workspace sync: total={} changed={} unchanged={}",
                    allPaths.size(),
                    hydrated,
                    Math.max(0, allPaths.size() - hydrated));
        }
        return hydrated;
    }

    private String normalizeHydrationPath(String path) {
        String normalized = normalizeManifestPath(path);
        if (normalized == null || normalized.startsWith("/sessions/")) {
            return null;
        }
        return normalized;
    }

    @Override
    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox(runtimeContext);
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        // F3-S2: out-of-call uploads (e.g. workspace/skill endpoints between chats) delegate to
        // the remote projection instead of throwing "No active sandbox".
        Sandbox active = activeSandbox(runtimeContext);
        if (active == null && remoteFallback != null) {
            return remoteFallback.uploadFiles(runtimeContext, files);
        }
        active = requireSandbox(runtimeContext);
        List<FileUploadResponse> results = new ArrayList<>(files.size());

        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();

            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = shellSingleQuote(path);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && "
                                + "printf '%s' '"
                                + base64Content
                                + "' | base64 -d > "
                                + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    results.add(FileUploadResponse.success(path));
                    // F3-S2: dual-write the projection so the file is readable between calls.
                    projectToRemote(runtimeContext, path, content);
                } else {
                    results.add(FileUploadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox active = activeSandbox(runtimeContext);
        if (active == null && remoteFallback != null) {
            return remoteFallback.downloadFiles(runtimeContext, paths);
        }
        active = requireSandbox(runtimeContext);
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());

        for (String path : paths) {
            try {
                String escapedPath = shellSingleQuote(path);
                String cmd = "base64 " + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    byte[] decoded =
                            Base64.getDecoder()
                                    .decode(
                                            result.stdout()
                                                    .trim()
                                                    .getBytes(StandardCharsets.UTF_8));
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    // ---- F3-S2: out-of-call delegation to the remote projection ----
    // When sandbox == null (between calls), file content IO delegates to remoteFallback so
    // MEMORY.md / skills / etc. stay readable and writable. Shell-class operations (execute,
    // grep, glob, delete, move) are NOT overridden — they inherit super which routes through
    // execute() and correctly throw outside a call (no sandbox to run them in).

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        if (activeSandbox(runtimeContext) == null && remoteFallback != null) {
            return remoteFallback.read(runtimeContext, filePath, offset, limit);
        }
        return super.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (activeSandbox(runtimeContext) == null && remoteFallback != null) {
            return remoteFallback.write(runtimeContext, filePath, content);
        }
        return super.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (activeSandbox(runtimeContext) == null && remoteFallback != null) {
            return remoteFallback.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (activeSandbox(runtimeContext) == null && remoteFallback != null) {
            return remoteFallback.exists(runtimeContext, path);
        }
        return super.exists(runtimeContext, path);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        if (activeSandbox(runtimeContext) == null && remoteFallback != null) {
            return remoteFallback.ls(runtimeContext, path);
        }
        return super.ls(runtimeContext, path);
    }

    /**
     * Best-effort dual-write of a successfully uploaded file to the remote projection. Failures are
     * logged and swallowed so a remote-store outage never breaks the in-sandbox write (the sandbox
     * copy is authoritative within a call).
     */
    private void projectToRemote(RuntimeContext runtimeContext, String path, byte[] content) {
        RemoteFilesystem fallback = remoteFallback;
        if (fallback == null) {
            return;
        }
        try {
            fallback.uploadFiles(runtimeContext, List.of(Map.entry(path, content)));
        } catch (Exception e) {
            log.warn("[sandbox-fs] remote projection failed for path {}: {}", path, e.getMessage());
        }
    }

    /**
     * Projects regular files from the live sandbox workspace archive into the remote fallback.
     *
     * <p>This captures files created or changed via shell commands inside the sandbox. After a
     * complete archive scan it reconciles only files that were present in the previous sandbox
     * projection manifest but are absent from the current archive. This keeps delete/move semantics
     * correct without scanning or deleting unrelated filesystem-backed data such as session mirrors.
     */
    public int projectSandboxWorkspaceToRemote(RuntimeContext runtimeContext) throws Exception {
        Sandbox active = activeSandbox(runtimeContext);
        RemoteFilesystem fallback = remoteFallback;
        if (active == null || fallback == null) {
            return 0;
        }

        boolean incremental = active.hasPersistentWorkspace();
        Map<String, VolumeSyncEntry> previous =
                incremental ? readVolumeSyncManifest(runtimeContext, active) : Map.of();
        Map<String, Long> remoteBefore =
                incremental ? normalizedRemoteVersions(runtimeContext, fallback) : Map.of();
        Map<String, String> localHashes = new LinkedHashMap<>();
        Set<String> remoteConflicts = new LinkedHashSet<>();
        Set<String> remoteDeletes = new LinkedHashSet<>();

        try (InputStream archive = active.persistWorkspace();
                TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            List<Map.Entry<String, byte[]>> batch = new ArrayList<>(REMOTE_PROJECTION_BATCH_SIZE);
            Set<String> currentProjection = new LinkedHashSet<>();
            int projected = 0;
            int scanned = 0;
            int unchanged = 0;
            long totalBytes = 0;
            boolean completeScan = true;
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                String path = toRemoteProjectionPath(entry.getName());
                if (path == null) {
                    log.warn(
                            "[sandbox-fs] Skipping unsafe workspace archive entry: {}",
                            entry.getName());
                    continue;
                }
                if (REMOTE_PROJECTION_MANIFEST.equals(path) || VOLUME_SYNC_MANIFEST.equals(path)) {
                    continue;
                }
                currentProjection.add(path);
                scanned++;
                long size = entry.getSize();
                if (size > REMOTE_PROJECTION_MAX_FILE_BYTES) {
                    log.warn(
                            "[sandbox-fs] Skipping oversized workspace file {} ({} bytes)",
                            path,
                            size);
                    continue;
                }
                if (scanned > REMOTE_PROJECTION_MAX_FILES) {
                    log.warn(
                            "[sandbox-fs] Workspace remote projection hit file limit {}",
                            REMOTE_PROJECTION_MAX_FILES);
                    completeScan = false;
                    break;
                }
                if (size > 0 && totalBytes + size > REMOTE_PROJECTION_MAX_TOTAL_BYTES) {
                    log.warn(
                            "[sandbox-fs] Workspace remote projection hit byte limit {}",
                            REMOTE_PROJECTION_MAX_TOTAL_BYTES);
                    completeScan = false;
                    break;
                }

                byte[] content = readEntryBytes(tar, path);
                if (totalBytes + content.length > REMOTE_PROJECTION_MAX_TOTAL_BYTES) {
                    log.warn(
                            "[sandbox-fs] Workspace remote projection hit byte limit {}",
                            REMOTE_PROJECTION_MAX_TOTAL_BYTES);
                    completeScan = false;
                    break;
                }
                totalBytes += content.length;
                String hash = sha256(content);
                localHashes.put(path, hash);
                if (incremental) {
                    VolumeSyncEntry old = previous.get(path);
                    long remoteVersion = remoteBefore.getOrDefault(path, 0L);
                    boolean localUnchanged = old != null && old.sha256().equals(hash);
                    boolean remoteDeleted =
                            old != null
                                    && old.remoteVersion() != 0L
                                    && !remoteBefore.containsKey(path);
                    boolean remoteChanged =
                            old != null
                                    && (remoteDeleted
                                            || (remoteVersion != 0L
                                                    && old.remoteVersion() != remoteVersion));
                    if (remoteChanged) {
                        // Never overwrite a browser/API update made while the sandbox was active.
                        // Preserve a concurrently changed sandbox copy as a visible conflict file.
                        remoteConflicts.add(path);
                        if (remoteDeleted) {
                            remoteDeletes.add(path);
                        }
                        if (localUnchanged) {
                            unchanged++;
                        } else {
                            batch.add(Map.entry(conflictProjectionPath(path), content));
                            projected++;
                            if (batch.size() >= REMOTE_PROJECTION_BATCH_SIZE) {
                                flushProjectionBatch(runtimeContext, fallback, batch);
                            }
                        }
                        continue;
                    }
                    if (localUnchanged
                            && remoteVersion != 0L
                            && old.remoteVersion() == remoteVersion) {
                        unchanged++;
                        continue;
                    }
                }
                batch.add(Map.entry(path, content));
                projected++;

                if (batch.size() >= REMOTE_PROJECTION_BATCH_SIZE) {
                    flushProjectionBatch(runtimeContext, fallback, batch);
                }
            }
            if (!batch.isEmpty()) {
                flushProjectionBatch(runtimeContext, fallback, batch);
            }
            int deleted = 0;
            if (completeScan) {
                deleted =
                        incremental
                                ? reconcileVolumeProjection(
                                        runtimeContext,
                                        fallback,
                                        currentProjection,
                                        previous,
                                        remoteBefore)
                                : reconcileRemoteProjection(
                                        runtimeContext, fallback, currentProjection);
                writeProjectionManifest(runtimeContext, fallback, currentProjection);
            }
            if (incremental && completeScan) {
                Map<String, Long> refreshed = normalizedRemoteVersions(runtimeContext, fallback);
                Map<String, VolumeSyncEntry> next = new LinkedHashMap<>();
                for (Map.Entry<String, String> local : localHashes.entrySet()) {
                    if (!remoteConflicts.contains(local.getKey())) {
                        next.put(
                                local.getKey(),
                                new VolumeSyncEntry(
                                        refreshed.getOrDefault(local.getKey(), 0L),
                                        local.getValue()));
                    }
                }
                // Keep the previous entry as a tombstone hint. The next acquire compares it with
                // the absent remote key and removes the stale local file from the Volume.
                for (String deletedPath : remoteDeletes) {
                    VolumeSyncEntry old = previous.get(deletedPath);
                    if (old != null) {
                        next.put(deletedPath, old);
                    }
                }
                writeVolumeSyncManifest(runtimeContext, active, next);
            }
            if (projected > 0 || incremental) {
                log.info(
                        "[sandbox-fs] Workspace projection: scanned={} uploaded={} unchanged={}"
                                + " conflicts={} bytes={} deleted={}",
                        scanned,
                        projected,
                        unchanged,
                        remoteConflicts.size(),
                        totalBytes,
                        deleted);
            }
            return projected;
        }
    }

    private int reconcileVolumeProjection(
            RuntimeContext runtimeContext,
            RemoteFilesystem fallback,
            Set<String> currentProjection,
            Map<String, VolumeSyncEntry> previous,
            Map<String, Long> remoteVersions) {
        int deleted = 0;
        for (Map.Entry<String, VolumeSyncEntry> old : previous.entrySet()) {
            String path = old.getKey();
            if (currentProjection.contains(path)) {
                continue;
            }
            long remoteVersion = remoteVersions.getOrDefault(path, 0L);
            if (remoteVersion != 0L && remoteVersion != old.getValue().remoteVersion()) {
                // A browser/API writer changed a file that the sandbox deleted. Preserve the
                // newer remote value and let the next acquire restore it into the Volume.
                continue;
            }
            try {
                fallback.delete(runtimeContext, path);
                notifyProjectionDeleted(runtimeContext, path);
                deleted++;
            } catch (Exception e) {
                log.warn(
                        "[sandbox-fs] Failed to delete stale Volume projection {}: {}",
                        path,
                        e.getMessage());
            }
        }
        return deleted;
    }

    private int reconcileRemoteProjection(
            RuntimeContext runtimeContext,
            RemoteFilesystem fallback,
            Set<String> currentProjection) {
        Set<String> previousProjection = readProjectionManifest(runtimeContext, fallback);
        int deleted = 0;
        for (String previousPath : previousProjection) {
            if (currentProjection.contains(previousPath)) {
                continue;
            }
            try {
                fallback.delete(runtimeContext, previousPath);
                notifyProjectionDeleted(runtimeContext, previousPath);
                deleted++;
            } catch (Exception e) {
                log.warn(
                        "[sandbox-fs] Failed to delete stale remote projection {}: {}",
                        previousPath,
                        e.getMessage());
            }
        }
        return deleted;
    }

    private void flushProjectionBatch(
            RuntimeContext runtimeContext,
            RemoteFilesystem fallback,
            List<Map.Entry<String, byte[]>> batch) {
        fallback.uploadFiles(runtimeContext, batch);
        for (Map.Entry<String, byte[]> entry : batch) {
            notifyProjectionWritten(runtimeContext, entry.getKey(), entry.getValue());
        }
        batch.clear();
    }

    private void notifyProjectionWritten(
            RuntimeContext runtimeContext, String path, byte[] content) {
        try {
            projectionSink.onProjectedFile(runtimeContext, path, content);
        } catch (Exception e) {
            log.warn("[sandbox-fs] projection sink failed for {}: {}", path, e.getMessage());
        }
    }

    private void notifyProjectionDeleted(RuntimeContext runtimeContext, String path) {
        try {
            projectionSink.onDeletedFile(runtimeContext, path);
        } catch (Exception e) {
            log.warn("[sandbox-fs] projection sink delete failed for {}: {}", path, e.getMessage());
        }
    }

    private Set<String> readProjectionManifest(
            RuntimeContext runtimeContext, RemoteFilesystem fallback) {
        ReadResult result = fallback.read(runtimeContext, REMOTE_PROJECTION_MANIFEST, 0, 0);
        if (!result.isSuccess()
                || result.fileData() == null
                || result.fileData().content() == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String line : result.fileData().content().split("\n")) {
            String path = normalizeManifestPath(line);
            if (path != null) {
                out.add(path);
            }
        }
        return out;
    }

    private void writeProjectionManifest(
            RuntimeContext runtimeContext,
            RemoteFilesystem fallback,
            Set<String> currentProjection) {
        String manifest =
                currentProjection.stream().sorted().collect(Collectors.joining("\n", "", "\n"));
        fallback.uploadFiles(
                runtimeContext,
                List.of(
                        Map.entry(
                                REMOTE_PROJECTION_MANIFEST,
                                manifest.getBytes(StandardCharsets.UTF_8))));
    }

    private byte[] readEntryBytes(TarArchiveInputStream tar, String path) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long bytes = 0;
        int read;
        while ((read = tar.read(buffer)) != -1) {
            bytes += read;
            if (bytes > REMOTE_PROJECTION_MAX_FILE_BYTES) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "Workspace file exceeds remote projection limit: " + path);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String toRemoteProjectionPath(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || ".".equals(normalized) || normalized.startsWith("/")) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return null;
        }
        return "/" + String.join("/", segments);
    }

    private String normalizeManifestPath(String line) {
        if (line == null || line.isBlank() || line.indexOf('\0') >= 0) {
            return null;
        }
        String normalized = line.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (REMOTE_PROJECTION_MANIFEST.equals(normalized)
                || VOLUME_SYNC_MANIFEST.equals(normalized)) {
            return null;
        }
        return toRemoteProjectionPath(normalized.substring(1));
    }

    private Map<String, Long> normalizedRemoteVersions(
            RuntimeContext runtimeContext, RemoteFilesystem fallback) {
        Map<String, Long> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : fallback.fileVersions(runtimeContext).entrySet()) {
            String path = normalizeHydrationPath(entry.getKey());
            if (path != null) {
                normalized.put(path, entry.getValue() != null ? entry.getValue() : 0L);
            }
        }
        return Map.copyOf(normalized);
    }

    private Map<String, VolumeSyncEntry> readVolumeSyncManifest(
            RuntimeContext runtimeContext, Sandbox target) {
        try {
            String relative = VOLUME_SYNC_MANIFEST.substring(1);
            ExecResult result =
                    target.exec(
                            runtimeContext,
                            "if test -f "
                                    + shellSingleQuote(relative)
                                    + "; then base64 "
                                    + shellSingleQuote(relative)
                                    + "; fi",
                            30);
            if (!result.ok() || result.truncated() || result.stdout() == null) {
                return Map.of();
            }
            byte[] json = Base64.getMimeDecoder().decode(result.stdout());
            Map<String, VolumeSyncEntry> decoded =
                    MANIFEST_MAPPER.readValue(json, SYNC_MANIFEST_TYPE);
            Map<String, VolumeSyncEntry> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, VolumeSyncEntry> entry : decoded.entrySet()) {
                String path = normalizeManifestPath(entry.getKey());
                if (path != null && entry.getValue() != null) {
                    normalized.put(path, entry.getValue());
                }
            }
            return Map.copyOf(normalized);
        } catch (Exception e) {
            log.warn("[sandbox-fs] Ignoring unreadable Volume sync manifest: {}", e.getMessage());
            return Map.of();
        }
    }

    private void writeVolumeSyncManifest(
            RuntimeContext runtimeContext, Sandbox target, Map<String, VolumeSyncEntry> entries) {
        try {
            byte[] json = MANIFEST_MAPPER.writeValueAsBytes(entries);
            String encoded = Base64.getEncoder().encodeToString(json);
            String relative = VOLUME_SYNC_MANIFEST.substring(1);
            String temp = relative + ".b64";
            ExecResult initialize =
                    target.exec(
                            runtimeContext,
                            "mkdir -p .agentscope && : > " + shellSingleQuote(temp),
                            30);
            if (!initialize.ok()) {
                throw new IllegalStateException(initialize.combinedOutput());
            }
            for (int offset = 0; offset < encoded.length(); offset += 4000) {
                String chunk = encoded.substring(offset, Math.min(offset + 4000, encoded.length()));
                ExecResult append =
                        target.exec(
                                runtimeContext,
                                "printf '%s' '" + chunk + "' >> " + shellSingleQuote(temp),
                                30);
                if (!append.ok()) {
                    throw new IllegalStateException(append.combinedOutput());
                }
            }
            ExecResult commit =
                    target.exec(
                            runtimeContext,
                            "base64 -d "
                                    + shellSingleQuote(temp)
                                    + " > "
                                    + shellSingleQuote(relative)
                                    + " && rm -f "
                                    + shellSingleQuote(temp),
                            30);
            if (!commit.ok()) {
                throw new IllegalStateException(commit.combinedOutput());
            }
        } catch (Exception e) {
            log.warn("[sandbox-fs] Failed to persist Volume sync manifest: {}", e.getMessage());
        }
    }

    private void removeRemoteDeletedVolumeFiles(
            RuntimeContext runtimeContext,
            Sandbox target,
            Map<String, VolumeSyncEntry> previous,
            Map<String, Long> remoteVersions)
            throws Exception {
        List<String> deleted =
                previous.keySet().stream()
                        .filter(path -> !remoteVersions.containsKey(path))
                        .toList();
        for (int offset = 0; offset < deleted.size(); offset += REMOTE_PROJECTION_BATCH_SIZE) {
            List<String> batch =
                    deleted.subList(
                            offset,
                            Math.min(deleted.size(), offset + REMOTE_PROJECTION_BATCH_SIZE));
            String command =
                    batch.stream()
                            .map(path -> shellSingleQuote(path.substring(1)))
                            .collect(Collectors.joining(" ", "rm -f -- ", ""));
            ExecResult result = target.exec(runtimeContext, command, 30);
            if (!result.ok()) {
                throw new IllegalStateException(
                        "Failed to remove files deleted from remote workspace: "
                                + result.combinedOutput());
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String conflictProjectionPath(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return "/generated/conflicts/"
                + UUID.randomUUID().toString().substring(0, 8)
                + "-"
                + safeName;
    }

    private record VolumeSyncEntry(long remoteVersion, String sha256) {
        private VolumeSyncEntry {
            sha256 = sha256 != null ? sha256 : "";
        }
    }

    private Sandbox activeSandbox(RuntimeContext runtimeContext) {
        if (runtimeContext != null) {
            Sandbox scoped = runtimeContext.get(Sandbox.class);
            if (scoped != null) {
                return scoped;
            }
            if (remoteFallback != null) {
                return null;
            }
        }
        return sandbox;
    }

    private Sandbox requireSandbox(RuntimeContext runtimeContext) {
        Sandbox s = activeSandbox(runtimeContext);
        if (s == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        return s;
    }

    private String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
