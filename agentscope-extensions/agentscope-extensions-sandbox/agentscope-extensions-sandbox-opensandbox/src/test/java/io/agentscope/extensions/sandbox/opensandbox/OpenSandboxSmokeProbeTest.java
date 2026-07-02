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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Manual OpenSandbox end-to-end probe.
 *
 * <p>Requires a live OpenSandbox server. It is skipped by default so unit tests do not depend on
 * local Docker/Kubernetes runtime infrastructure.
 */
class OpenSandboxSmokeProbeTest {

    @Test
    void createsExecutesPersistsAndDeletesSandbox() throws Exception {
        assumeTrue(
                "true".equalsIgnoreCase(env("OPENSANDBOX_SMOKE_ENABLED", "")),
                "set OPENSANDBOX_SMOKE_ENABLED=true to run the live OpenSandbox probe");

        String marker = env("OPENSANDBOX_MARKER", "opensandbox-java-smoke-ok");
        String workspaceRoot = env("OPENSANDBOX_WORKSPACE_ROOT", "/workspace");

        OpenSandboxClientOptions opt = new OpenSandboxClientOptions();
        opt.setApiBaseUrl(env("OPENSANDBOX_API_BASE_URL", "http://127.0.0.1:18081/v1"));
        opt.setApiKey(env("OPENSANDBOX_API_KEY", ""));
        opt.setExecdAccessToken(env("OPENSANDBOX_EXECD_ACCESS_TOKEN", ""));
        opt.setImage(env("OPENSANDBOX_IMAGE", "ubuntu:latest"));
        opt.setWorkspaceRoot(workspaceRoot);
        opt.setWaitTimeoutSeconds(envInt("OPENSANDBOX_WAIT_TIMEOUT", 180));
        opt.setSandboxTimeoutSeconds(envInt("OPENSANDBOX_SANDBOX_TIMEOUT", 300));
        opt.setDefaultExecTimeoutSeconds(envInt("OPENSANDBOX_COMMAND_TIMEOUT", 60));

        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(workspaceRoot);

        Sandbox sandbox = new OpenSandboxClient().create(spec, null, opt);
        assertNotNull(sandbox.getState());

        try {
            long start = System.currentTimeMillis();
            sandbox.start();
            System.out.println(
                    "[opensandbox-probe] started in "
                            + (System.currentTimeMillis() - start)
                            + "ms");

            String command =
                    "mkdir -p "
                            + shellSingleQuote(workspaceRoot)
                            + " && printf '%s\\n' "
                            + shellSingleQuote(marker)
                            + " > "
                            + shellSingleQuote(workspaceRoot + "/report.txt")
                            + " && cat "
                            + shellSingleQuote(workspaceRoot + "/report.txt");
            ExecResult result = sandbox.exec(null, command, 60);
            System.out.println("[opensandbox-probe] stdout=" + result.stdout());
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains(marker));

            SandboxException.ExecException nonZero =
                    assertThrows(
                            SandboxException.ExecException.class,
                            () -> sandbox.exec(null, "exit 7", 30));
            assertEquals(7, nonZero.getExitCode());

            try (InputStream archive = sandbox.persistWorkspace()) {
                assertTrue(archive.readAllBytes().length > 0);
            }
        } finally {
            sandbox.shutdown();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
