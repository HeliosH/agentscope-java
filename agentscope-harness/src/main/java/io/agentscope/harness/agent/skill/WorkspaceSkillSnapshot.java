/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan;
import io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lazy skill view over the authoritative Run manifest, never over its mutable sandbox copy. */
@SuppressWarnings("deprecation")
final class WorkspaceSkillSnapshot {
    private final Map<String, Map<String, WorkspaceFile>> skills = new LinkedHashMap<>();
    private final String source;

    WorkspaceSkillSnapshot(WorkspaceRestorePlan plan, String base, String source) {
        this.source = source;
        String prefix =
                base.isBlank() || base.equals(".")
                        ? ""
                        : new WorkspaceFile(base, new byte[0]).path() + "/";
        for (var file : plan.files()) {
            if (!file.path().startsWith(prefix)) continue;
            String relative = file.path().substring(prefix.length());
            int slash = relative.indexOf('/');
            if (slash <= 0) continue;
            String name = relative.substring(0, slash);
            if (name.startsWith(".") || name.startsWith("_")) continue;
            String resource = relative.substring(slash + 1);
            var files = skills.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
            if (files.putIfAbsent(resource, file) != null)
                throw new IllegalStateException("SKILL_SNAPSHOT_PATH_CONFLICT");
        }
    }

    List<AgentSkill> all() {
        return skills.entrySet().stream()
                .filter(e -> e.getValue().containsKey("SKILL.md"))
                .sorted(Map.Entry.comparingByKey())
                .map(
                        e -> {
                            var skill =
                                    SkillUtil.createFrom(
                                            text(e.getValue().get("SKILL.md")), null, source);
                            if (!e.getKey().equals(skill.getName()))
                                throw new IllegalStateException("SKILL_SNAPSHOT_NAME_MISMATCH");
                            return skill;
                        })
                .toList();
    }

    SkillResources resources(String name) {
        var files = skills.get(name);
        if (files == null || !files.containsKey("SKILL.md")) return SkillResources.empty();
        var immutable = Map.copyOf(files);
        String version =
                StepSnapshot.fingerprint(
                        immutable.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(
                                        e ->
                                                Map.of(
                                                        "path",
                                                        e.getKey(),
                                                        "sha256",
                                                        e.getValue().sha256(),
                                                        "size",
                                                        e.getValue().size()))
                                .toList());
        return new SkillResources() {
            @Override
            public Optional<String> document() {
                return read("SKILL.md");
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public Optional<String> read(String relativePath) {
                return readBinary(relativePath)
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public Optional<byte[]> readBinary(String relativePath) {
                // Exact manifest keys only: aliases, traversal, absolute paths and newly created
                // sandbox files cannot acquire a frozen skill identity.
                if (relativePath == null
                        || relativePath.startsWith("/")
                        || relativePath.indexOf('\\') >= 0
                        || List.of(relativePath.split("/", -1)).stream()
                                .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
                    return Optional.empty();
                var file = immutable.get(relativePath);
                return file == null ? Optional.empty() : Optional.of(file.content());
            }

            @Override
            public List<String> list() {
                return immutable.keySet().stream().sorted().toList();
            }
        };
    }

    private static String text(WorkspaceFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }
}
