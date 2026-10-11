/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSpoolDirectoryTest {
    @TempDir Path parent;

    @Test
    void reclaimsInactiveSpoolsButNeverDeletesAnotherLivePool() throws Exception {
        var first = WorkspaceSpoolDirectory.open(parent);
        Path staleRoot = first.path();
        Path stale = first.create("stale-");
        Files.writeString(stale, "private bytes");
        try (var second = WorkspaceSpoolDirectory.open(parent)) {
            Path live = second.create("live-");
            assertTrue(Files.exists(stale));
            first.close();
            try (var third = WorkspaceSpoolDirectory.open(parent)) {
                assertFalse(Files.exists(staleRoot));
                assertTrue(Files.exists(live));
                assertTrue(Files.exists(third.path()));
                if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
                    assertEquals(
                            PosixFilePermissions.fromString("rwx------"),
                            Files.getPosixFilePermissions(third.path()));
            }
        } finally {
            first.close();
        }
    }

    @Test
    void directoriesWithUnknownFilesOrSymlinksAreNotReclaimed() throws Exception {
        Path unknown, linked;
        try (var first = WorkspaceSpoolDirectory.open(parent)) {
            unknown = first.path();
            Files.writeString(unknown.resolve("important.txt"), "not a managed spool");
        }
        Path outside = parent.resolve("outside.txt");
        Files.writeString(outside, "preserve");
        try (var second = WorkspaceSpoolDirectory.open(parent)) {
            linked = second.path();
            Files.createSymbolicLink(linked.resolve("linked123.part"), outside);
        }
        try (var third = WorkspaceSpoolDirectory.open(parent)) {
            assertTrue(Files.exists(unknown.resolve("important.txt")));
            assertTrue(Files.exists(linked));
            assertEquals("preserve", Files.readString(outside));
        }
    }

    @Test
    void aNewProcessCanReclaimBytesLeftByAnAbruptlyHaltedOwner() throws Exception {
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                WorkspaceSpoolDirectoryTest.class.getName(),
                                parent.toString())
                        .redirectErrorStream(true)
                        .start();
        try {
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            var lines =
                    new String(
                                    process.getInputStream().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .lines()
                            .toList();
            Path stale = Path.of(lines.get(lines.size() - 1));
            assertTrue(stale.startsWith(parent.toRealPath()));
            assertTrue(Files.exists(stale));
            try (var next = WorkspaceSpoolDirectory.open(parent)) {
                assertFalse(Files.exists(stale.getParent()));
                assertTrue(Files.exists(next.path()));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var directory = WorkspaceSpoolDirectory.open(Path.of(args[0]));
        var file = directory.create("halted-");
        Files.writeString(file, "uncommitted temporary bytes");
        System.out.println(file);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
