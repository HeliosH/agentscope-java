/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.saas.orchestration.DurableTaskExecutor.DependencyContext;
import io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the bounded, task-specific context supplied to a durable subagent. */
public final class TaskContextAssembler {

    private static final int MAX_DEPENDENCY_OUTPUT_CHARS = 4000;

    public String assemble(ExecutionRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("taskId", request.taskId().toString());
        context.put("goal", request.title());
        context.put("input", jsonValue(request.inputJson(), Map.of()));
        context.put("expectedOutputs", jsonValue(request.expectedOutputJson(), List.of()));
        context.put("acceptanceCriteria", jsonValue(request.acceptanceJson(), List.of()));
        context.put(
                "dependencies",
                request.dependencies().stream().map(TaskContextAssembler::dependency).toList());
        context.put(
                "resultContract",
                Map.of(
                        "requiredFields",
                        List.of(
                                "status",
                                "summary",
                                "evidence",
                                "artifactRefs",
                                "followUpTasks",
                                "usage"),
                        "largeOutputs",
                        "Persist files and logs as artifacts; return references only."));
        return "Execute only the following durable task. Do not assume access to the full parent "
                + "conversation. Satisfy every acceptance criterion and return the required "
                + "structured result.\n<task_context>\n"
                + JsonUtils.getJsonCodec().toJson(context)
                + "\n</task_context>";
    }

    private static Map<String, Object> dependency(DependencyContext dependency) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", dependency.taskId().toString());
        value.put("title", dependency.title());
        value.put(
                "result",
                jsonValue(
                        truncate(dependency.outputJson(), MAX_DEPENDENCY_OUTPUT_CHARS), Map.of()));
        value.put("artifactRefs", dependency.artifactRefs());
        return value;
    }

    private static Object jsonValue(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            Object value = JsonUtils.getJsonCodec().fromJson(json, Object.class);
            if (value instanceof String nested) {
                value = JsonUtils.getJsonCodec().fromJson(nested, Object.class);
            }
            return value;
        } catch (RuntimeException ignored) {
            return Map.of("unparsed", truncate(json, MAX_DEPENDENCY_OUTPUT_CHARS));
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
