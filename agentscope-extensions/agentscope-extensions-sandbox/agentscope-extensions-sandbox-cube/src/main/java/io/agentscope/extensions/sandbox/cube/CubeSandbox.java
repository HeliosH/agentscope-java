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
package io.agentscope.extensions.sandbox.cube;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cube sandbox instance backed by a CubeSandbox microVM. Communicates with the Cube platform via
 * E2B-compatible REST API for lifecycle and Connect+protobuf envd for command execution.
 *
 * <p>Only TAR-based workspace persistence is supported (no E2B native snapshots). Cube's private
 * deployment model means sandboxes are typically managed by the platform with TTL-based cleanup.
 */
final class CubeSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(CubeSandbox.class);

    private final CubeSandboxState state;
    private final CubeSandboxClientOptions opt;
    private final CubePlatformHttp platform;

    // Lazily created — depends on state having sandboxId and domain
    private volatile CubeEnvdProcessClient envd;

    CubeSandbox(CubeSandboxState state, CubeSandboxClientOptions opt) {
        super(state);
        this.state = state;
        this.opt = opt;
        this.platform =
                new CubePlatformHttp(
                        CubeHttpClients.create(opt),
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        opt);
    }

    @Override
    public void start() throws Exception {
        // Warn if bind mounts are configured (Cube may not support host-path binds)
        WorkspaceSpec ws = state.getWorkspaceSpec();
        if (ws != null) {
            for (WorkspaceEntry entry : ws.getEntries().values()) {
                if (entry instanceof io.agentscope.harness.agent.sandbox.layout.BindMountEntry) {
                    log.warn(
                            "Cube sandbox does not support bind mounts; entry '{}' will be ignored",
                            entry.getClass().getSimpleName());
                }
            }
        }
        ensureSandbox();
        verifyHostMounts();
        super.start();
        try {
            applyCommonSkillsOverlay();
        } catch (Exception error) {
            try {
                super.stop();
            } catch (Exception stopError) {
                error.addSuppressed(stopError);
            }
            throw error;
        }
    }

    @Override
    public void shutdown() throws Exception {
        if (state.isSandboxOwned() && state.getSandboxId() != null) {
            platform.killSandbox(state.getSandboxId());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return envd().runShell(state, state.getWorkspaceRoot(), command, timeoutSeconds);
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        // TAR mode only: run tar inside the sandbox and stream binary stdout
        String cmd =
                "tar -cf -"
                        + nestedMountExcludes()
                        + " -C "
                        + shellSingleQuote(state.getWorkspaceRoot())
                        + " .";
        byte[] tarBytes = envd().runShellBinaryStdout(state, state.getWorkspaceRoot(), cmd, 120);
        return new ByteArrayInputStream(tarBytes);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        byte[] raw = archive.readAllBytes();
        if (raw.length == 0) {
            return;
        }
        // Write base64-encoded tar in chunks via shell printf, then decode+extract.
        // Uses only base64 + tar (GNU coreutils) — no Python dependency.
        String b64 = Base64.getEncoder().encodeToString(raw);
        String tmpFile = "/tmp/agentscope-ws.b64";

        int chunkSize = 4000;
        for (int i = 0; i < b64.length(); i += chunkSize) {
            String chunk = b64.substring(i, Math.min(i + chunkSize, b64.length()));
            // base64 chars are all safe for single-quoted printf; escape single quotes only
            String escaped = chunk.replace("'", "'\\''");
            String operator = i == 0 ? ">" : ">>";
            String writeCmd = "printf '%s' '" + escaped + "' " + operator + " " + tmpFile;
            envd().runShell(state, state.getWorkspaceRoot(), writeCmd, 60);
        }

        String extractCmd =
                "base64 -d "
                        + tmpFile
                        + " | tar -xf -"
                        + nestedMountExcludes()
                        + " -C "
                        + shellSingleQuote(state.getWorkspaceRoot())
                        + "; status=$?; rm -f "
                        + tmpFile
                        + "; exit $status";
        envd().runShell(state, state.getWorkspaceRoot(), extractCmd, 120);
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        envd().runShell(
                        state,
                        state.getWorkspaceRoot(),
                        "mkdir -p " + state.getWorkspaceRoot(),
                        30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        if (hasWritableWorkspaceMount()) {
            log.debug("Skipping deletion of Cube host-mounted workspace");
            return;
        }
        try {
            envd().runShell(
                            state,
                            state.getWorkspaceRoot(),
                            "rm -rf " + state.getWorkspaceRoot(),
                            30);
        } catch (Exception e) {
            log.warn("Failed to destroy workspace in sandbox: {}", e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return state.getWorkspaceRoot();
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    // ---- internals ----

    private void ensureSandbox() throws Exception {
        if (state.getSandboxId() == null || state.getSandboxId().isBlank()) {
            // Create new sandbox
            int timeout = opt.getSandboxTimeoutSeconds();
            var json = platform.createSandbox(opt.getTemplateId(), timeout, state.getHostMounts());
            CubePlatformHttp.applySandboxFields(state, json);
            applyDefaultDomain();
            log.info("Created Cube sandbox id={}", state.getSandboxId());
        } else {
            // Try to connect to existing sandbox
            try {
                int timeout = opt.getSandboxTimeoutSeconds();
                var json = platform.connectSandbox(state.getSandboxId(), timeout);
                CubePlatformHttp.applySandboxFields(state, json);
                applyDefaultDomain();
                log.debug("Connected to Cube sandbox id={}", state.getSandboxId());
            } catch (Exception e) {
                log.warn(
                        "Failed to connect to Cube sandbox id={}, recreating: {}",
                        state.getSandboxId(),
                        e.getMessage());
                state.setSandboxId(null);
                state.setWorkspaceRootReady(false);
                ensureSandbox();
            }
        }
    }

    private void applyDefaultDomain() {
        if (state.getSandboxDomain() == null || state.getSandboxDomain().isBlank()) {
            state.setSandboxDomain(opt.getDomain());
        }
    }

    private CubeEnvdProcessClient envd() throws Exception {
        if (envd == null) {
            synchronized (this) {
                if (envd == null) {
                    envd = new CubeEnvdProcessClient(opt);
                }
            }
        }
        return envd;
    }

    private void verifyHostMounts() throws Exception {
        if (!state.isVerifyHostMounts() || state.getHostMounts().isEmpty()) {
            return;
        }
        String checks =
                state.getHostMounts().stream()
                        .map(
                                mount ->
                                        "test -e "
                                                + shellSingleQuote(mount.mountPath())
                                                + " || { echo "
                                                + shellSingleQuote(
                                                        "missing Cube host mount: "
                                                                + mount.mountPath())
                                                + " >&2; exit 42; }")
                        .collect(Collectors.joining(" && "));
        ExecResult result = envd().runShell(state, "/", checks, 30);
        if (!result.ok()) {
            throw new IllegalStateException(
                    "Cube host-mount verification failed: " + result.stderr());
        }
    }

    private void applyCommonSkillsOverlay() throws Exception {
        String source = state.getCommonSkillsMountPath();
        if (source == null || source.isBlank()) {
            return;
        }
        String target = state.getCommonSkillsTargetPath();
        if (target == null || target.isBlank()) {
            target = state.getWorkspaceRoot() + "/skills";
        }
        String command =
                "set -eu; source="
                        + shellSingleQuote(source)
                        + "; target="
                        + shellSingleQuote(target)
                        + "; mkdir -p \"$target\"; if [ -d \"$source\" ]; then "
                        + "for destination in \"$target\"/*; do [ -L \"$destination\" ] || "
                        + "continue; link=$(readlink \"$destination\") || continue; "
                        + "case \"$link\" in \"$source\"/*) [ -e \"$destination\" ] || "
                        + "rm -f \"$destination\" ;; esac; done; "
                        + "for skill in \"$source\"/*; do [ -d \"$skill\" ] || continue; "
                        + "name=${skill##*/}; destination=\"$target/$name\"; "
                        + "if [ ! -e \"$destination\" ] && [ ! -L \"$destination\" ]; then "
                        + "ln -s \"$skill\" \"$destination\"; fi; done; fi";
        ExecResult result = envd().runShell(state, state.getWorkspaceRoot(), command, 30);
        if (!result.ok()) {
            throw new IllegalStateException(
                    "Failed to apply Cube common Skills overlay: " + result.stderr());
        }
    }

    private boolean hasWritableWorkspaceMount() {
        Path workspace = Path.of(state.getWorkspaceRoot()).normalize();
        return state.getHostMounts().stream()
                .anyMatch(
                        mount ->
                                !mount.readOnly()
                                        && Path.of(mount.mountPath())
                                                .normalize()
                                                .equals(workspace));
    }

    private String nestedMountExcludes() {
        Path workspace = Path.of(state.getWorkspaceRoot()).normalize();
        List<String> excludes =
                state.getHostMounts().stream()
                        .map(CubeHostMount::mountPath)
                        .map(Path::of)
                        .map(Path::normalize)
                        .filter(path -> path.startsWith(workspace) && !path.equals(workspace))
                        .map(workspace::relativize)
                        .map(Path::toString)
                        .map(relative -> " --exclude=" + shellSingleQuote("./" + relative))
                        .toList();
        return String.join("", excludes);
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
