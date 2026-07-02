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
package io.agentscope.extensions.sandbox.opensandbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sandbox implementation backed by OpenSandbox lifecycle API and execd command API. */
public class OpenSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(OpenSandbox.class);

    private static final String CONTROL_CWD = "/";
    private static final int TAR_TIMEOUT_SECONDS = 300;
    private static final int B64_CHUNK = 4000;

    private final OpenSandboxState openSandboxState;
    private final OpenSandboxClientOptions opt;
    private final OpenSandboxPlatformHttp platform;

    public OpenSandbox(OpenSandboxState state, OpenSandboxClientOptions opt) {
        super(state);
        this.openSandboxState = state;
        this.opt = opt != null ? opt : new OpenSandboxClientOptions();
        this.platform = new OpenSandboxPlatformHttp(this.opt);
    }

    @Override
    public void start() throws Exception {
        if (WorkspaceMountSupport.hasBindMounts(openSandboxState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-opensandbox] WorkspaceSpec contains bind_mount entries; "
                            + "OpenSandbox adapter does not apply host bind mounts yet.");
        }
        ensureSandbox();
        super.start();
    }

    @Override
    public void shutdown() throws Exception {
        if (!openSandboxState.isSandboxOwned()) {
            return;
        }
        String id = openSandboxState.getSandboxId();
        if (id != null && !id.isBlank()) {
            platform.deleteSandbox(id);
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return platform.runCommand(openSandboxState, getWorkspaceRoot(), command, timeoutSeconds);
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String root = openSandboxState.getWorkspaceRoot();
        StringBuilder script = new StringBuilder("rm -f /tmp/agentscope-ws.tar && tar ");
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(
                        openSandboxState.getWorkspaceSpec())) {
            script.append(ex).append(' ');
        }
        script.append("-cf /tmp/agentscope-ws.tar -C ")
                .append(shellSingleQuote(root))
                .append(" . && base64 /tmp/agentscope-ws.tar; ")
                .append("status=$?; rm -f /tmp/agentscope-ws.tar; exit $status");
        ExecResult result =
                platform.runCommand(openSandboxState, root, script.toString(), TAR_TIMEOUT_SECONDS);
        byte[] tar =
                Base64.getMimeDecoder().decode(result.stdout().getBytes(StandardCharsets.UTF_8));
        return new ByteArrayInputStream(tar);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = openSandboxState.getWorkspaceRoot();
        String b64 = Base64.getEncoder().encodeToString(archive.readAllBytes());
        platform.runCommand(openSandboxState, CONTROL_CWD, "rm -f /tmp/agentscope-ws.b64", 30);
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            String chunk = b64.substring(i, Math.min(b64.length(), i + B64_CHUNK));
            platform.runCommand(
                    openSandboxState,
                    CONTROL_CWD,
                    "printf '%s' " + shellSingleQuote(chunk) + " >> /tmp/agentscope-ws.b64",
                    30);
        }
        platform.runCommand(
                openSandboxState,
                CONTROL_CWD,
                "base64 -d /tmp/agentscope-ws.b64 | tar xf - -C "
                        + shellSingleQuote(root)
                        + "; status=$?; rm -f /tmp/agentscope-ws.b64; exit $status",
                TAR_TIMEOUT_SECONDS);
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        platform.runCommand(
                openSandboxState,
                CONTROL_CWD,
                "mkdir -p " + shellSingleQuote(openSandboxState.getWorkspaceRoot()),
                30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        try {
            platform.runCommand(
                    openSandboxState,
                    CONTROL_CWD,
                    "rm -rf " + shellSingleQuote(openSandboxState.getWorkspaceRoot()),
                    30);
        } catch (Exception e) {
            log.debug("[sandbox-opensandbox] destroy workspace best-effort: {}", e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return openSandboxState.getWorkspaceRoot();
    }

    @Override
    protected int getDefaultExecTimeoutSeconds() {
        return opt.getDefaultExecTimeoutSeconds();
    }

    private void ensureSandbox() throws Exception {
        String id = openSandboxState.getSandboxId();
        if (id == null || id.isBlank()) {
            createSandbox();
            return;
        }
        try {
            JsonNode existing = platform.getSandbox(id);
            String state = OpenSandboxPlatformHttp.state(existing);
            if ("running".equalsIgnoreCase(state)) {
                return;
            }
            if ("pending".equalsIgnoreCase(state)
                    || "pausing".equalsIgnoreCase(state)
                    || "resuming".equalsIgnoreCase(state)) {
                platform.waitUntilRunning(id);
                return;
            }
            log.warn("[sandbox-opensandbox] sandbox {} not reusable, state={}", id, state);
            openSandboxState.setWorkspaceRootReady(false);
            createSandbox();
        } catch (Exception e) {
            log.warn(
                    "[sandbox-opensandbox] connect failed, recreating sandbox: {}", e.getMessage());
            openSandboxState.setWorkspaceRootReady(false);
            createSandbox();
        }
    }

    private void createSandbox() throws Exception {
        JsonNode created = platform.createSandbox(openSandboxState.getSessionId());
        platform.applySandboxFields(openSandboxState, created);
        if (openSandboxState.getSandboxId() == null || openSandboxState.getSandboxId().isBlank()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "OpenSandbox create response missing id: " + created);
        }
        platform.waitUntilRunning(openSandboxState.getSandboxId());
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
