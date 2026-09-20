/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable description of one final model request and its selected execution capabilities. */
public record StepSnapshot(
        String stepId,
        long sequence,
        Identity identity,
        String modelName,
        String modelId,
        String modelRouteVersion,
        String environmentId,
        String environmentVersion,
        Integer inputTokenBudget,
        String toolRegistrationVersion,
        String toolSchemaHash,
        String promptHash,
        String optionsHash,
        String policyHash,
        String extensionSetHash,
        List<String> toolNames) {
    public StepSnapshot {
        toolNames = List.copyOf(toolNames);
    }

    /** Source-compatible constructor for callers that do not yet provide an environment version. */
    public StepSnapshot(
            String stepId,
            long sequence,
            Identity identity,
            String modelName,
            String modelId,
            String modelRouteVersion,
            Integer inputTokenBudget,
            String toolRegistrationVersion,
            String toolSchemaHash,
            String promptHash,
            String optionsHash,
            String policyHash,
            String extensionSetHash,
            List<String> toolNames) {
        this(
                stepId,
                sequence,
                identity,
                modelName,
                modelId,
                modelRouteVersion,
                null,
                null,
                inputTokenBudget,
                toolRegistrationVersion,
                toolSchemaHash,
                promptHash,
                optionsHash,
                policyHash,
                extensionSetHash,
                toolNames);
    }

    /** Optional orchestration identity supplied by the hosting application, without core SaaS coupling. */
    public record Identity(String runId, String agentRunId, String taskId, String attemptId) {}

    public static StepSnapshot capture(
            String stepId,
            long sequence,
            RuntimeContext context,
            ModelCallInput input,
            PermissionContextState permissions,
            String toolRegistrationVersion) {
        ModelContextProfile profile = null;
        if (input.model() instanceof ContextWindowAwareModel aware) {
            profile =
                    context != null && context.get(ContextWindowAwareModel.MODEL_ID_KEY) != null
                            ? aware.resolveContextProfile(context)
                            : aware.resolveContextProfile(input.messages());
        }
        RuntimeToolScope scope = RuntimeToolScope.current(context);
        ExecutionEnvironmentSnapshot environment =
                context == null ? null : context.get(ExecutionEnvironmentSnapshot.class);
        List<String> names =
                input.tools() == null
                        ? List.of()
                        : input.tools().stream().map(tool -> tool.getName()).sorted().toList();
        return new StepSnapshot(
                stepId,
                sequence,
                context == null ? null : context.get(Identity.class),
                input.model().getModelName(),
                profile == null ? null : profile.modelId(),
                input.model() instanceof io.agentscope.core.model.StepBindableModel.BoundModel bound
                        ? bound.routeVersion()
                        : null,
                environment == null ? null : environment.environmentId(),
                environment == null ? null : environment.version(),
                profile == null ? null : profile.inputTokenBudget(),
                toolRegistrationVersion,
                fingerprint(input.tools()),
                fingerprint(input.messages()),
                fingerprint(input.options()),
                fingerprint(permissions),
                scope == null ? null : scope.configurationHash(),
                names);
    }

    /** Canonical JSON hashing: map iteration order must not create a different runtime version. */
    public static String fingerprint(Object value) {
        Object json =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(value), Object.class);
        return RuntimeToolScope.hash(JsonUtils.getJsonCodec().toJson(canonical(json)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(StepSnapshot::canonical).toList();
        }
        return value;
    }
}
