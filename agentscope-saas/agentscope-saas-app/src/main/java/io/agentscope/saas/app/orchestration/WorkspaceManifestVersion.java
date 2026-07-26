/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Canonical digest for an immutable workspace checkpoint manifest. */
final class WorkspaceManifestVersion {

    private WorkspaceManifestVersion() {}

    static String compute(List<Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.stream()
                    .sorted(Comparator.comparing(Entry::logicalPath))
                    .forEach(
                            entry -> {
                                digest.update(entry.logicalPath().getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) 0);
                                digest.update(
                                        entry.fileVersionId()
                                                .toString()
                                                .getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) 0);
                                digest.update(entry.sha256().getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) '\n');
                            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    record Entry(String logicalPath, UUID fileVersionId, String sha256) {}
}
