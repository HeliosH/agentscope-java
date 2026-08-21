/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.extension;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable identity and dependency declaration for a deployment-approved extension. */
public record ExtensionManifest(
        String id,
        String version,
        List<String> dependencies,
        Set<ContributionType> contributionTypes) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");

    public ExtensionManifest {
        id = requireMatch(id, ID, "extension id");
        version = requireMatch(version, VERSION, "extension version");
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        contributionTypes = contributionTypes == null ? Set.of() : Set.copyOf(contributionTypes);
        if (dependencies.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("extension dependencies must not contain null");
        }
        if (dependencies.stream().distinct().count() != dependencies.size()) {
            throw new IllegalArgumentException("extension dependencies must be unique");
        }
        for (String dependency : dependencies) {
            requireMatch(dependency, ID, "extension dependency");
            if (id.equals(dependency)) {
                throw new IllegalArgumentException("extension must not depend on itself");
            }
        }
    }

    public String identity() {
        return id + "@" + version;
    }

    private static String requireMatch(String value, Pattern pattern, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return normalized;
    }

    /** Supported extension surfaces. Arbitrary in-process code loading is intentionally absent. */
    public enum ContributionType {
        TOOL,
        MCP,
        SKILL,
        MIDDLEWARE,
        SUBAGENT,
        SANDBOX_PROVIDER,
        MEMORY_PROJECTOR
    }
}
