/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral workspace checkpoint prepared by trusted orchestration code.
 *
 * <p>The sandbox lifecycle middleware hydrates these immutable files after the backend workspace
 * starts and before agent execution begins.
 */
public final class WorkspaceRestorePlan {

    private final String checkpointUri;
    private final String workspaceVersion;
    private final List<WorkspaceFile> files;

    public WorkspaceRestorePlan(
            String checkpointUri, String workspaceVersion, List<WorkspaceFile> files) {
        if (checkpointUri == null || checkpointUri.isBlank()) {
            throw new IllegalArgumentException("checkpointUri is required");
        }
        if (workspaceVersion == null || workspaceVersion.isBlank()) {
            throw new IllegalArgumentException("workspaceVersion is required");
        }
        this.checkpointUri = checkpointUri;
        this.workspaceVersion = workspaceVersion;
        this.files = files == null ? List.of() : List.copyOf(files);
    }

    public String checkpointUri() {
        return checkpointUri;
    }

    public String workspaceVersion() {
        return workspaceVersion;
    }

    public List<WorkspaceFile> files() {
        return files;
    }

    /** One workspace-relative immutable file. */
    public static final class WorkspaceFile {

        private final String path;
        private final byte[] content;
        private final long size;
        private final String sha256;
        private final ContentSource source;

        public WorkspaceFile(String path, byte[] content) {
            this.path = normalizePath(path);
            this.content = content != null ? content.clone() : new byte[0];
            this.size = this.content.length;
            this.sha256 = digest(this.content);
            this.source = () -> new java.io.ByteArrayInputStream(this.content);
        }

        public WorkspaceFile(String path, long size, String sha256, ContentSource source) {
            this.path = normalizePath(path);
            if (size < 0 || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid immutable workspace file descriptor");
            this.size = size;
            this.sha256 = sha256;
            this.source = java.util.Objects.requireNonNull(source);
            this.content = null;
        }

        public String path() {
            return path;
        }

        public byte[] content() {
            if (content != null) return content.clone();
            if (size > 32L * 1024 * 1024)
                throw new IllegalStateException("Use streaming access for large workspace files");
            try (var out = new java.io.ByteArrayOutputStream()) {
                transferTo(out, 8192);
                return out.toByteArray();
            } catch (Exception error) {
                throw new IllegalStateException("Workspace file unavailable", error);
            }
        }

        public long size() {
            return size;
        }

        public String sha256() {
            return sha256;
        }

        public void transferTo(java.io.OutputStream out, int bufferBytes) throws Exception {
            if (bufferBytes < 4096 || bufferBytes > 1024 * 1024)
                throw new IllegalArgumentException("Invalid workspace transfer buffer");
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = source.open()) {
                byte[] buffer = new byte[bufferBytes];
                long copied = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read > size - copied)
                        throw new java.io.IOException("WORKSPACE_INPUT_INTEGRITY");
                    copied += read;
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                if (copied != size
                        || !java.util.HexFormat.of().formatHex(digest.digest()).equals(sha256))
                    throw new java.io.IOException("WORKSPACE_INPUT_INTEGRITY");
            }
        }

        private static String digest(byte[] bytes) {
            try {
                return java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private static String normalizePath(String path) {
            if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("workspace restore path is required");
            }
            String normalized = path.trim().replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            List<String> segments = new ArrayList<>();
            for (String segment : normalized.split("/")) {
                if (segment.isBlank() || ".".equals(segment)) {
                    continue;
                }
                if ("..".equals(segment)) {
                    throw new IllegalArgumentException(
                            "workspace restore path must stay inside the workspace");
                }
                segments.add(segment);
            }
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("workspace restore path is required");
            }
            return String.join("/", segments);
        }
    }

    @FunctionalInterface
    public interface ContentSource {
        java.io.InputStream open() throws Exception;
    }
}
