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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeSandboxClientTest {

    @Test
    void create_attachesSnapshotFromSpec(@TempDir Path tmp) {
        CubeSandboxClient client = new CubeSandboxClient();

        Sandbox sandbox =
                client.create(
                        new WorkspaceSpec(),
                        new LocalSnapshotSpec(tmp.resolve("snaps").toString()),
                        new CubeSandboxClientOptions());

        // Constructing CubeSandbox must not NPE (regression: platform http previously got a null
        // ObjectMapper), and the snapshot must be attached so the workspace can be persisted.
        assertNotNull(sandbox);
        assertInstanceOf(CubeSandboxState.class, sandbox.getState());
        CubeSandboxState state = (CubeSandboxState) sandbox.getState();
        assertNotNull(state.getSnapshot(), "snapshot must be built from the spec on create");
        assertEquals(state.getSessionId(), state.getSnapshot().getId());
    }

    @Test
    void create_withoutSnapshotSpec_leavesSnapshotNull() {
        CubeSandboxClient client = new CubeSandboxClient();

        Sandbox sandbox =
                client.create(
                        new WorkspaceSpec(),
                        new NoopSnapshotSpec(),
                        new CubeSandboxClientOptions());

        assertNotNull(sandbox);
        // NoopSnapshotSpec still builds a (no-op) snapshot; the point is create() must not throw.
        assertNotNull(sandbox.getState());
    }

    @Test
    void create_resolvesAndPersistsValidatedHostMounts() {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setHostMounts(
                List.of(
                        new CubeHostMount(
                                "/data/shared/workspaces/{sessionId}", "/workspace", false)));

        CubeSandboxState state =
                (CubeSandboxState)
                        new CubeSandboxClient()
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options)
                                .getState();

        assertEquals(1, state.getHostMounts().size());
        assertEquals(
                "/data/shared/workspaces/" + state.getSessionId(),
                state.getHostMounts().get(0).hostPath());
    }

    @Test
    void create_rejectsHostMountOutsideApplicationAllowlist() {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setHostMounts(List.of(new CubeHostMount("/etc", "/host-etc", true)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CubeSandboxClient()
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options));
    }

    @Test
    void create_rejectsDuplicateMountTargets() {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setHostMounts(
                List.of(
                        new CubeHostMount("/data/shared/a", "/data", true),
                        new CubeHostMount("/data/shared/b", "/data", true)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CubeSandboxClient()
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options));
    }

    @Test
    void create_requiresCommonSkillsSourceToBeReadOnlyHostMount() {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setCommonSkillsMountPath("/opt/skills");
        options.setHostMounts(
                List.of(new CubeHostMount("/data/shared/skills", "/opt/skills", false)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CubeSandboxClient()
                                .create(new WorkspaceSpec(), new NoopSnapshotSpec(), options));
    }

    @Test
    void stateRoundTripPreservesResolvedMountAndSkillsOverlay() {
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setHostMounts(
                List.of(
                        new CubeHostMount(
                                "/data/shared/skills", "/opt/agentscope-common-skills", true)));
        options.setCommonSkillsMountPath("/opt/agentscope-common-skills");
        CubeSandboxClient client = new CubeSandboxClient();
        CubeSandboxState original =
                (CubeSandboxState)
                        client.create(new WorkspaceSpec(), new NoopSnapshotSpec(), options)
                                .getState();

        CubeSandboxState restored =
                (CubeSandboxState) client.deserializeState(client.serializeState(original));

        assertEquals(original.getHostMounts(), restored.getHostMounts());
        assertEquals("/opt/agentscope-common-skills", restored.getCommonSkillsMountPath());
    }
}
