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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSandboxFilesystem} that delegates execution to a live {@link Sandbox}.
 *
 * <p>Stable proxy created at agent build time; a fresh {@link Sandbox} is injected on each call
 * via the volatile {@code sandbox} field by {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

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

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Wires the remote projection backend. Must be called once at agent build time, before any
     * call. Passing {@code null} disables projection (the legacy behaviour: all out-of-call IO
     * throws).
     */
    public void configureRemoteFallback(RemoteFilesystem fallback) {
        this.remoteFallback = fallback;
    }

    /** Returns whether a remote projection backend is configured. */
    public boolean hasRemoteFallback() {
        return remoteFallback != null;
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
        Sandbox active = requireSandbox();
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
        if (sandbox == null && remoteFallback != null) {
            return remoteFallback.uploadFiles(runtimeContext, files);
        }
        Sandbox active = requireSandbox();
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
        if (sandbox == null && remoteFallback != null) {
            return remoteFallback.downloadFiles(runtimeContext, paths);
        }
        Sandbox active = requireSandbox();
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
        if (sandbox == null && remoteFallback != null) {
            return remoteFallback.read(runtimeContext, filePath, offset, limit);
        }
        return super.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (sandbox == null && remoteFallback != null) {
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
        if (sandbox == null && remoteFallback != null) {
            return remoteFallback.edit(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (sandbox == null && remoteFallback != null) {
            return remoteFallback.exists(runtimeContext, path);
        }
        return super.exists(runtimeContext, path);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        if (sandbox == null && remoteFallback != null) {
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

    private Sandbox requireSandbox() {
        Sandbox s = sandbox;
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
