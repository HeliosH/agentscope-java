/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.filesystem.model;

import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import java.util.ArrayDeque;

/** A command and its workspace-relative working directory, kept separate until execution. */
public record ShellExecutionRequest(
        String command, String workingDirectory, Integer timeoutSeconds) {

    public ShellExecutionRequest {
        workingDirectory = normalizeDirectory(workingDirectory);
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
    }

    /** Quoted compatibility adapter for existing POSIX-shell providers. */
    public String legacyShellCommand() {
        if (workingDirectory == null) {
            return command;
        }
        return "cd " + FilesystemUtils.shellQuote("./" + workingDirectory) + " && " + command;
    }

    private static String normalizeDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        if (directory.indexOf('\0') >= 0
                || directory.startsWith("/")
                || directory.startsWith("\\")
                || directory.matches("^[a-zA-Z]:.*")) {
            throw new IllegalArgumentException(
                    "working_directory must be relative to the workspace");
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : directory.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("working_directory escapes the workspace");
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return segments.isEmpty() ? "." : String.join("/", segments);
    }
}
