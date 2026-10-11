/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/** Private process-owned temporary bytes. Only a proven inactive owner can be reclaimed. */
final class WorkspaceSpoolDirectory implements AutoCloseable {
    private static final String PREFIX = "agentscope-transfer-v1-";
    private static final String OWNER = ".owner.lock";
    private static final byte[] MARKER =
            "agentscope-workspace-spool-v1\n".getBytes(StandardCharsets.US_ASCII);
    private final Path path;
    private final FileChannel channel;
    private final FileLock owner;
    private boolean closed;

    private WorkspaceSpoolDirectory(Path path, FileChannel channel, FileLock owner) {
        this.path = path;
        this.channel = channel;
        this.owner = owner;
    }

    static WorkspaceSpoolDirectory open(Path parent) throws IOException {
        parent = parent.toRealPath();
        Path path = parent.resolve(PREFIX + UUID.randomUUID());
        if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
            Files.createDirectory(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(path);
        FileChannel channel = null;
        FileLock owner = null;
        try {
            channel =
                    FileChannel.open(
                            path.resolve(OWNER),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            owner = channel.lock();
            ByteBuffer marker = ByteBuffer.wrap(MARKER);
            while (marker.hasRemaining()) channel.write(marker);
            channel.force(false);
            var directory = new WorkspaceSpoolDirectory(path, channel, owner);
            directory.reclaim(parent, Files.getOwner(path, LinkOption.NOFOLLOW_LINKS));
            return directory;
        } catch (IOException | RuntimeException error) {
            if (owner != null)
                try {
                    owner.close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            if (channel != null)
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            try {
                Files.deleteIfExists(path.resolve(OWNER));
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    Path create(String prefix) throws IOException {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_.-]{3,64}"))
            throw new IllegalArgumentException("Invalid workspace spool prefix");
        return Files.createTempFile(path, prefix, ".part");
    }

    Path path() {
        return path;
    }

    private void reclaim(Path parent, UserPrincipal principal) {
        try (var directories = Files.newDirectoryStream(parent, PREFIX + "*")) {
            int scanned = 0;
            for (Path candidate : directories) {
                if (++scanned > 100) break;
                if (candidate.equals(path)) continue;
                try {
                    reclaimOne(candidate, principal);
                } catch (IOException | OverlappingFileLockException ignored) {
                    // Another owner/reaper, unknown files, or an inaccessible directory is
                    // preserved.
                }
            }
        } catch (IOException ignored) {
            // Reclamation failure cannot make other processes' files ours.
        }
    }

    private static void reclaimOne(Path path, UserPrincipal principal) throws IOException {
        String suffix = path.getFileName().toString().substring(PREFIX.length());
        try {
            if (!UUID.fromString(suffix).toString().equals(suffix)) return;
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !principal.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) return;
        Path marker = path.resolve(OWNER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !principal.equals(Files.getOwner(marker, LinkOption.NOFOLLOW_LINKS))) return;
        try (var channel =
                        FileChannel.open(
                                marker,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null || channel.size() != MARKER.length) return;
            var bytes = ByteBuffer.allocate(MARKER.length);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) return;
            if (!Arrays.equals(MARKER, bytes.array())) return;
            var files = new ArrayList<Path>();
            try (var entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    if (entry.equals(marker)) continue;
                    if (files.size() >= 1024
                            || !entry.getFileName()
                                    .toString()
                                    .matches("[A-Za-z0-9_.-]+[0-9]+\\.part")
                            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            || !principal.equals(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS)))
                        return;
                    files.add(entry);
                }
            }
            for (Path file : files) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(marker);
        Files.deleteIfExists(path);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            owner.close();
        } finally {
            channel.close();
        }
    }
}
