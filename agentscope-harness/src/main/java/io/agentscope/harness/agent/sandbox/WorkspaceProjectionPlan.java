/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.sandbox;

import java.io.DataOutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Complete immutable file inventory captured before any release projection is written. */
public record WorkspaceProjectionPlan(List<Entry> entries, int scannedFiles, long totalBytes) {
    public WorkspaceProjectionPlan {
        entries = List.copyOf(entries);
        if (scannedFiles < entries.size() || totalBytes < 0)
            throw new IllegalArgumentException("Invalid workspace inventory totals");
        if (entries.stream().map(Entry::path).distinct().count() != entries.size())
            throw new IllegalArgumentException("Duplicate workspace publication path");
        if (entries.stream().map(Entry::sourcePath).distinct().count() != entries.size())
            throw new IllegalArgumentException("Duplicate workspace inventory source");
        if (entries.stream().mapToLong(Entry::sizeBytes).reduce(0L, Math::addExact) != totalBytes)
            throw new IllegalArgumentException("Workspace inventory byte mismatch");
    }

    public record Entry(
            String sourcePath, String path, long sizeBytes, String sha256, boolean unchanged) {
        public Entry {
            Objects.requireNonNull(sourcePath);
            Objects.requireNonNull(path);
            if (sourcePath.isBlank()
                    || path.isBlank()
                    || sizeBytes < 0
                    || sha256 == null
                    || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid workspace inventory entry");
        }
    }

    public String version() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var out =
                    new DataOutputStream(
                            new DigestOutputStream(
                                    java.io.OutputStream.nullOutputStream(), digest))) {
                out.writeInt(1);
                out.writeInt(scannedFiles);
                out.writeInt(entries.size());
                out.writeLong(totalBytes);
                for (var entry :
                        entries.stream().sorted(Comparator.comparing(Entry::path)).toList()) {
                    out.writeUTF(entry.sourcePath());
                    out.writeUTF(entry.path());
                    out.writeLong(entry.sizeBytes());
                    out.writeUTF(entry.sha256());
                    out.writeBoolean(entry.unchanged());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalStateException("Workspace inventory fingerprint failed", error);
        }
    }
}
