/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VolumeWorkspaceCacheTest {
    @TempDir Path base;

    @Test
    void cacheMatchesSkipTransfersAndChangedOrMissingFilesAreReturned() throws Exception {
        var sandbox = new LocalVolume(base);
        var a = file("inputs/a.txt", "kept");
        var b = file("inputs/b.txt", "new");
        Files.createDirectories(sandbox.root.resolve("inputs"));
        Files.writeString(sandbox.root.resolve(a.path()), "kept");
        Files.writeString(sandbox.root.resolve(b.path()), "old");
        Files.writeString(sandbox.root.resolve("unlisted.txt"), "stale");
        var plan = plan(List.of(a, b));
        assertEquals(List.of(b), prepare(sandbox, plan));
        assertFalse(Files.exists(sandbox.root.resolve("unlisted.txt")));
        assertEquals("kept", Files.readString(sandbox.root.resolve(a.path())));
        Files.writeString(sandbox.root.resolve(b.path()), "new");
        assertTrue(prepare(sandbox, plan).isEmpty());
    }

    @Test
    void emptyPlanPrunesOnlyCurrentRunAndPreservesOtherRunsAndUserFiles() throws Exception {
        var sandbox = new LocalVolume(base);
        Files.writeString(base.resolve("user.txt"), "user");
        var other = Files.createDirectories(base.resolve(".agentscope/runs/other"));
        Files.writeString(other.resolve("keep.txt"), "other");
        Files.writeString(sandbox.root.resolve("stale.txt"), "old");
        assertTrue(prepare(sandbox, plan(List.of())).isEmpty());
        assertFalse(Files.exists(sandbox.root.resolve("stale.txt")));
        Files.writeString(sandbox.root.resolve("outputs/new.txt"), "new deliverable");
        assertEquals("user", Files.readString(base.resolve("user.txt")));
        assertEquals("other", Files.readString(other.resolve("keep.txt")));
    }

    @Test
    void symlinkCacheIsRemovedWithoutFollowingItsTarget() throws Exception {
        var sandbox = new LocalVolume(base);
        var outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "private");
        Files.createSymbolicLink(sandbox.root.resolve("inputs"), outside);
        var expected = file("inputs/a.txt", "authorized");
        assertEquals(List.of(expected), prepare(sandbox, plan(List.of(expected))));
        assertTrue(Files.isDirectory(sandbox.root.resolve("inputs")));
        assertFalse(Files.isSymbolicLink(sandbox.root.resolve("inputs")));
        assertEquals("private", Files.readString(outside.resolve("a.txt")));
    }

    @Test
    void matchingCacheCannotHideLostAuthoritativeObjectOrUnscopedRoot() throws Exception {
        var sandbox = new LocalVolume(base);
        var a = file("a.txt", "kept");
        Files.writeString(sandbox.root.resolve("a.txt"), "kept");
        var absent =
                new WorkspaceRestorePlan.WorkspaceFile(
                        a.path(),
                        a.size(),
                        a.sha256(),
                        () -> {
                            throw new java.io.IOException("lost object");
                        });
        assertThrows(java.io.IOException.class, () -> prepare(sandbox, plan(List.of(absent))));
        sandbox.state.setWorkspaceScopeKey(null);
        assertThrows(IllegalStateException.class, () -> prepare(sandbox, plan(List.of(a))));
    }

    private static List<WorkspaceRestorePlan.WorkspaceFile> prepare(
            LocalVolume sandbox, WorkspaceRestorePlan plan) throws Exception {
        return VolumeWorkspaceCache.prepare(
                RuntimeContext.empty(), sandbox, plan, WorkspaceTransferResources.DEFAULT);
    }

    private static WorkspaceRestorePlan plan(List<WorkspaceRestorePlan.WorkspaceFile> files) {
        return new WorkspaceRestorePlan("workspace://test", "v1", files);
    }

    private static WorkspaceRestorePlan.WorkspaceFile file(String path, String content) {
        return new WorkspaceRestorePlan.WorkspaceFile(
                path, content.getBytes(StandardCharsets.UTF_8));
    }

    static class LocalVolume implements Sandbox {
        final SandboxState state = new SandboxState() {};
        final Path root;

        LocalVolume(Path base) throws Exception {
            base = base.toRealPath();
            root =
                    Files.createDirectories(
                            base.resolve(".agentscope/runs")
                                    .resolve(
                                            io.agentscope.core.tool.StepSnapshot.fingerprint(
                                                    "run/test")));
            state.setWorkspaceBaseRoot(base.toString());
            state.setWorkspaceScopeKey("run/test");
            var spec = new WorkspaceSpec();
            spec.setRoot(root.toString());
            state.setWorkspaceSpec(spec);
        }

        public void start() {}

        public void stop() {}

        public void close() {}

        public boolean isRunning() {
            return true;
        }

        public SandboxState getState() {
            return state;
        }

        public boolean hasPersistentWorkspace() {
            return true;
        }

        public ExecResult exec(RuntimeContext context, String command, Integer timeout)
                throws Exception {
            var process =
                    new ProcessBuilder("/bin/bash", "-c", command).directory(root.toFile()).start();
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err =
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ExecResult(process.waitFor(), out, err, false);
        }

        public InputStream persistWorkspace() {
            throw new UnsupportedOperationException();
        }

        public void hydrateWorkspace(InputStream stream) {
            throw new UnsupportedOperationException();
        }
    }
}
