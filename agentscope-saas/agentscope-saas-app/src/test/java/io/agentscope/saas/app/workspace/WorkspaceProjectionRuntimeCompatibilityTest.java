/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

/** Exercises Harness projection with the SaaS application's dependency graph, not Harness's. */
class WorkspaceProjectionRuntimeCompatibilityTest {
    @Test
    void freezesAndProjectsATarUsingTheApplicationRuntimeDependencies() throws Exception {
        var bytes = new ByteArrayOutputStream();
        byte[] content = "runtime-compatible".getBytes(StandardCharsets.UTF_8);
        try (var tar = new TarArchiveOutputStream(bytes)) {
            ArchiveOutputStream compatible = tar;
            var entry = new TarArchiveEntry("outputs/runtime.txt");
            entry.setSize(content.length);
            compatible.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        var sandbox =
                new Sandbox() {
                    @Override
                    public void start() {}

                    @Override
                    public void stop() {}

                    @Override
                    public void close() {}

                    @Override
                    public boolean isRunning() {
                        return true;
                    }

                    @Override
                    public SandboxState getState() {
                        return new SandboxState() {};
                    }

                    @Override
                    public ExecResult exec(RuntimeContext c, String command, Integer timeout) {
                        return new ExecResult(0, "", "", false);
                    }

                    @Override
                    public InputStream persistWorkspace() {
                        return new ByteArrayInputStream(bytes.toByteArray());
                    }

                    @Override
                    public void hydrateWorkspace(InputStream archive) {}
                };
        var context = RuntimeContext.empty();
        context.put(Sandbox.class, sandbox);
        var remote =
                new RemoteFilesystem(new InMemoryStore(), c -> List.of("runtime", "projection"));
        var filesystem = new SandboxBackedFilesystem();
        filesystem.configureRemoteFallback(remote);
        var report = filesystem.projectSandboxWorkspaceWithReport(context);
        assertThat(report.completeScan()).isTrue();
        assertThat(report.projectedFiles()).isEqualTo(1);
        assertThat(remote.read(context, "/outputs/runtime.txt", 0, 0).fileData().content())
                .isEqualTo("runtime-compatible");
    }
}
