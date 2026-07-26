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

        public WorkspaceFile(String path, byte[] content) {
            this.path = normalizePath(path);
            this.content = content != null ? content.clone() : new byte[0];
        }

        public String path() {
            return path;
        }

        public byte[] content() {
            return content.clone();
        }

        public long size() {
            return content.length;
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
}
