/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.sandbox.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalProcessRunnerTest {

    @TempDir Path workspace;

    @Test
    void drainsBothPipesBeyondCapacityAndRetainsTheirEnds() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    var result =
                            run(
                                    "i=0; while [ $i -lt 20000 ]; do printf 'abcdefgh12345678'; "
                                            + "printf 'ABCDEFGH12345678' >&2; i=$((i+1)); done; "
                                            + "printf STDOUT_END; printf STDERR_END >&2",
                                    Duration.ofSeconds(8));
                    assertEquals(0, result.exitCode());
                    assertTrue(result.truncated());
                    assertTrue(result.stdout().startsWith("abcdefgh"));
                    assertTrue(result.stdout().endsWith("STDOUT_END"));
                    assertTrue(result.stderr().endsWith("STDERR_END"));
                    assertTrue(result.stdout().length() < 4200);
                    assertTrue(result.stderr().length() < 4200);
                });
    }

    @Test
    void completionOnlyCommandReceivesStdinEof() throws Exception {
        var result = run("cat; printf DONE", Duration.ofSeconds(2));
        assertEquals(0, result.exitCode());
        assertEquals("DONE", result.stdout());
    }

    @Test
    void timeoutTerminatesAChildHoldingBothPipes() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    var result =
                            run("sleep 60 & echo $! > child.pid; wait", Duration.ofMillis(500));
                    assertTrue(result.timedOut());
                    assertEquals(124, result.exitCode());
                    assertChildTerminated();
                });
    }

    @Test
    void runtimeCancellationTerminatesAChild() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    long start = System.nanoTime();
                    var result =
                            LocalProcessRunner.run(
                                    builder("sleep 60 & echo $! > child.pid; wait"),
                                    Duration.ofSeconds(60),
                                    4096,
                                    () ->
                                            System.nanoTime() - start
                                                    > Duration.ofMillis(500).toNanos());
                    assertEquals(130, result.exitCode());
                    assertTrue(result.cancelled());
                    assertChildTerminated();
                });
    }

    @Test
    void threadInterruptionCleansUpChild() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                run("sleep 60 & echo $! > child.pid; wait", Duration.ofSeconds(60));
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        worker.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!Files.exists(workspace.resolve("child.pid")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertChildTerminated();
        } finally {
            worker.interrupt();
            worker.join(3000);
        }
    }

    @Test
    void utf8IsPreservedAcrossBufferBoundaryAndTruncatedSafely() {
        BoundedOutputBuffer exact = new BoundedOutputBuffer(7);
        byte[] bytes = "中文".getBytes(StandardCharsets.UTF_8);
        exact.append(bytes, 0, bytes.length);
        assertEquals("中文", exact.text());
        assertFalse(exact.truncated());
        exact.append(bytes, 0, bytes.length);
        assertTrue(exact.truncated());
        assertFalse(exact.text().contains("\ufffd"));
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputBuffer(0));
    }

    private LocalProcessRunner.Result run(String command, Duration timeout) throws Exception {
        return LocalProcessRunner.run(builder(command), timeout, 4096, () -> false);
    }

    private ProcessBuilder builder(String command) {
        return new ProcessBuilder("sh", "-c", command).directory(workspace.toFile());
    }

    private void assertChildTerminated() throws Exception {
        long pid = Long.parseLong(Files.readString(workspace.resolve("child.pid")).trim());
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "child must not survive its invocation: " + pid);
    }
}
