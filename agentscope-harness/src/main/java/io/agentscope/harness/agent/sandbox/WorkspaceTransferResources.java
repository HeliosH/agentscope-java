/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Process-wide transfer admission and pessimistic temporary disk reservations. */
public final class WorkspaceTransferResources {
    public static final WorkspaceTransferResources DEFAULT =
            new WorkspaceTransferResources(2, 1024L * 1024 * 1024, 1024 * 1024);

    private final Semaphore transfers;
    private final long maxSpoolBytes;
    private final int bufferBytes;
    private final long admissionWaitMillis;
    private final int maxSpools;
    private int reservedSpools;
    private WorkspaceSpoolDirectory directory;
    private long reservedBytes;

    public WorkspaceTransferResources(
            int concurrentTransfers, long maxSpoolBytes, int bufferBytes) {
        this(concurrentTransfers, maxSpoolBytes, bufferBytes, 30000);
    }

    public WorkspaceTransferResources(
            int concurrentTransfers,
            long maxSpoolBytes,
            int bufferBytes,
            long admissionWaitMillis) {
        if (concurrentTransfers < 1
                || maxSpoolBytes < 1
                || bufferBytes < 4096
                || bufferBytes > 1024 * 1024
                || admissionWaitMillis < 0
                || admissionWaitMillis > 300000)
            throw new IllegalArgumentException("Invalid workspace transfer resource budget");
        transfers = new Semaphore(concurrentTransfers, true);
        this.maxSpoolBytes = maxSpoolBytes;
        this.bufferBytes = bufferBytes;
        this.admissionWaitMillis = admissionWaitMillis;
        maxSpools = Math.addExact(Math.multiplyExact(concurrentTransfers, 8), 16);
    }

    public int bufferBytes() {
        return bufferBytes;
    }

    public Permit acquire() throws InterruptedException {
        if (!transfers.tryAcquire(admissionWaitMillis, TimeUnit.MILLISECONDS))
            throw new CapacityException("WORKSPACE_TRANSFER_BUSY");
        return new Permit();
    }

    public Spool spool(String prefix, long limit) throws IOException {
        if (limit < 1 || limit > maxSpoolBytes)
            throw new CapacityException("WORKSPACE_SPOOL_LIMIT");
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(admissionWaitMillis);
            while (limit > maxSpoolBytes - reservedBytes || reservedSpools >= maxSpools) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new CapacityException("WORKSPACE_SPOOL_BUSY");
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Workspace spool admission interrupted", interrupted);
                }
            }
            reservedBytes += limit;
            reservedSpools++;
        }
        Path path = null;
        try {
            path = spoolDirectory().create(prefix);
            if (Files.getFileStore(path).getUsableSpace() < limit)
                throw new IOException("Insufficient workspace spool disk space");
            return new Spool(path, limit);
        } catch (IOException | RuntimeException error) {
            if (path == null) release(limit);
            else {
                try {
                    Files.deleteIfExists(path);
                    release(limit);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }

    private synchronized WorkspaceSpoolDirectory spoolDirectory() throws IOException {
        if (directory == null)
            directory = WorkspaceSpoolDirectory.open(Path.of(System.getProperty("java.io.tmpdir")));
        // Keep the owner lock for the pool lifetime; do not unlock while a transfer is active.
        return directory;
    }

    public static long archiveLimit(WorkspaceTransferPolicy policy) {
        // POSIX extended headers and padding are budgeted independently of file payload bytes.
        return Math.addExact(
                policy.maxTotalBytes(),
                Math.addExact(Math.multiplyExact((long) policy.maxFiles(), 4096), 10240));
    }

    private synchronized void release(long bytes) {
        reservedBytes -= bytes;
        reservedSpools--;
        notifyAll();
    }

    public static final class CapacityException extends IllegalStateException {
        public CapacityException(String code) {
            super(code);
        }
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                transfers.release();
            }
        }
    }

    public final class Spool implements AutoCloseable {
        private final Path path;
        private final long limit;
        private boolean closed;
        private boolean opened;

        private Spool(Path path, long limit) {
            this.path = path;
            this.limit = limit;
        }

        public Path path() {
            return path;
        }

        public synchronized OutputStream output() throws IOException {
            if (closed || opened) throw new IOException("Workspace spool output already opened");
            opened = true;
            var delegate = Files.newOutputStream(path);
            return new OutputStream() {
                private long written;

                @Override
                public void write(int value) throws IOException {
                    require(1);
                    delegate.write(value);
                    written++;
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                    require(length);
                    delegate.write(bytes, offset, length);
                    written += length;
                }

                private void require(int count) throws IOException {
                    if (closed || count > limit - written)
                        throw new IOException("WORKSPACE_SPOOL_LIMIT");
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                // Retain the reservation if deletion fails: stale bytes still consume disk.
                Files.deleteIfExists(path);
                closed = true;
                release(limit);
            }
        }
    }
}
