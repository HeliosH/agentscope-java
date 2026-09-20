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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs a local process while continuously draining both pipes with bounded memory. */
public final class LocalProcessRunner {

    private LocalProcessRunner() {}

    public static Result run(
            ProcessBuilder builder, Duration timeout, int outputBytes, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        BoundedOutputBuffer stdout = new BoundedOutputBuffer(outputBytes);
        BoundedOutputBuffer stderr = new BoundedOutputBuffer(outputBytes);
        Process process = builder.start();
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        long started = System.nanoTime();
        byte[] scratch = new byte[8192];
        try {
            // This completion-only interface never accepts stdin. EOF prevents tools such as cat
            // from waiting forever for input that callers cannot send.
            process.getOutputStream().close();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Command execution interrupted");
                }
                process.descendants().forEach(descendants::add);
                int read = drainAvailable(process.getInputStream(), stdout, scratch);
                read += drainAvailable(process.getErrorStream(), stderr, scratch);
                if (cancelled.getAsBoolean()) {
                    terminate(process, descendants);
                    return new Result(
                            stdout.text(),
                            stderr.text(),
                            130,
                            stdout.truncated() || stderr.truncated(),
                            false,
                            true);
                }
                if (!process.isAlive()) {
                    // Drain the remainder without waiting for EOF: background children may have
                    // inherited a pipe. They must not keep a completed invocation waiting forever.
                    while (drainAvailable(process.getInputStream(), stdout, scratch)
                                    + drainAvailable(process.getErrorStream(), stderr, scratch)
                            > 0) {
                        if (System.nanoTime() - started >= timeout.toNanos()) {
                            break;
                        }
                    }
                    return new Result(
                            stdout.text(),
                            stderr.text(),
                            process.exitValue(),
                            stdout.truncated() || stderr.truncated(),
                            false,
                            false);
                }
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    terminate(process, descendants);
                    return new Result(
                            stdout.text(),
                            stderr.text(),
                            124,
                            stdout.truncated() || stderr.truncated(),
                            true,
                            false);
                }
                if (read == 0) {
                    process.waitFor(10, TimeUnit.MILLISECONDS);
                }
            }
        } finally {
            terminate(process, descendants);
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    private static int drainAvailable(
            InputStream stream, BoundedOutputBuffer buffer, byte[] scratch) throws IOException {
        int total = 0;
        // Bound each drain pass so a continuously writing stream cannot starve stderr, timeout,
        // or cancellation. Only read available bytes; never block on inherited descriptors.
        while (total < 65536) {
            int available = stream.available();
            if (available == 0) {
                break;
            }
            int count = stream.read(scratch, 0, Math.min(available, scratch.length));
            if (count < 0) {
                break;
            }
            buffer.append(scratch, 0, count);
            total += count;
        }
        return total;
    }

    private static void terminate(Process process, Set<ProcessHandle> descendants) {
        process.descendants().forEach(descendants::add);
        // Keep the parent alive while terminating children so it can reap them.
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            if (!descendants.isEmpty()) {
                try {
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            process.destroyForcibly();
        }
    }

    public record Result(
            String stdout,
            String stderr,
            int exitCode,
            boolean truncated,
            boolean timedOut,
            boolean cancelled) {}
}
