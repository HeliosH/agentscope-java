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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class OpenSandboxClientTest {

    @Test
    void serializesOpenSandboxStateWithSubtype() {
        OpenSandboxClient client = new OpenSandboxClient();
        Sandbox sandbox = client.create(new WorkspaceSpec(), null, new OpenSandboxClientOptions());
        OpenSandboxState state = (OpenSandboxState) sandbox.getState();
        state.setSandboxId("os-1");

        String json = client.serializeState(state);
        SandboxState restored = client.deserializeState(json);

        assertInstanceOf(OpenSandboxState.class, restored);
        assertEquals("os-1", ((OpenSandboxState) restored).getSandboxId());
    }

    @Test
    void defaultsUseWorkspaceRoot() {
        assertEquals(
                OpenSandboxClientOptions.DEFAULT_WORKSPACE_ROOT,
                new OpenSandboxClientOptions().getWorkspaceRoot());
        assertEquals(
                OpenSandboxClientOptions.DEFAULT_WORKSPACE_ROOT,
                new OpenSandboxState().getWorkspaceRoot());
    }
}
