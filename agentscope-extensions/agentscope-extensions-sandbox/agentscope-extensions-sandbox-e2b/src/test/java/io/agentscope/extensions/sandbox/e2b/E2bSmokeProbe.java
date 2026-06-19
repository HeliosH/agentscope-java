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
package io.agentscope.extensions.sandbox.e2b;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;

/**
 * Minimal end-to-end probe for the E2B sandbox: create → start → exec a trivial command and print
 * the exit code + stdout. Run with {@code E2B_API_KEY} env var. Verifies whether the envd
 * {@code process.Process/Start} stream yields a real exit code (vs. the historical {@code -1}).
 *
 * <p>Not a unit test — requires network + a live E2B key. Invoke manually.
 */
public class E2bSmokeProbe {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("E2B_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("E2B_API_KEY env var required");
        }
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(apiKey);
        // defaults: apiBaseUrl https://api.e2b.app, domain e2b.app, workspaceRoot /home/user

        E2bSandboxClient client = new E2bSandboxClient();
        Sandbox sandbox = client.create(new WorkspaceSpec(), null, opt);

        System.out.println("[probe] starting sandbox...");
        long t0 = System.currentTimeMillis();
        try {
            sandbox.start();
            System.out.println("[probe] started in " + (System.currentTimeMillis() - t0) + "ms");

            String cmd = "echo hello-e2b && id && pwd && mkdir -p /home/user/probe && echo done";
            long t1 = System.currentTimeMillis();
            ExecResult r = sandbox.exec(null, cmd, 60);
            long dt = System.currentTimeMillis() - t1;
            System.out.println("[probe] exec exit=" + r.exitCode() + " in " + dt + "ms");
            System.out.println("[probe] stdout=" + r.stdout());
            System.err.println("[probe] stderr=" + r.stderr());
            if (r.exitCode() != 0) {
                System.out.println("[probe] RESULT=FAIL exit=" + r.exitCode());
                return;
            }
            // Verify non-zero exit code is parsed correctly (envd 0.6.x status string path).
            ExecResult r2 = sandbox.exec(null, "exit 7", 30);
            System.out.println("[probe] exit-7 result exit=" + r2.exitCode());
            if (r2.exitCode() == 7) {
                System.out.println("[probe] RESULT=OK");
            } else {
                System.out.println(
                        "[probe] RESULT=PARTIAL exit-7 got=" + r2.exitCode() + " (expected 7)");
            }
        } finally {
            try {
                sandbox.shutdown();
            } catch (Exception e) {
                System.err.println("[probe] shutdown error: " + e.getMessage());
            }
        }
    }
}
