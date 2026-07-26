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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic completion boundary for durable tasks.
 *
 * <p>The gate receives only persisted task contracts and immutable result references. It has no
 * access to the executing Agent's conversation or mutable workspace.
 */
public final class CompletionGate {

    public static final String ERROR_CODE = "VERIFICATION_FAILED";

    public Decision evaluate(String expectedOutputJson, String acceptanceJson, String outputJson) {
        List<?> expected = jsonList(expectedOutputJson, "expected outputs");
        List<?> acceptance = jsonList(acceptanceJson, "acceptance criteria");
        boolean required = !expected.isEmpty() || !acceptance.isEmpty();
        if (!required) {
            return new Decision(true, false, List.of(), 0, 0);
        }

        Map<?, ?> output = jsonObject(outputJson, "task output");
        List<String> failures = new ArrayList<>();
        if (!"succeeded".equalsIgnoreCase(string(output.get("status")))) {
            failures.add("structured result status must be succeeded");
        }
        if (string(output.get("summary")).isBlank()) {
            failures.add("structured result summary is required");
        }
        List<?> evidence = list(output.get("evidence"));
        List<?> artifacts = list(output.get("artifactRefs"));
        if (output.get("followUpTasks") != null
                && !(output.get("followUpTasks") instanceof List<?>)) {
            failures.add("followUpTasks must be an array");
        }
        if (!(output.get("usage") instanceof Map<?, ?>)) {
            failures.add("usage must be an object");
        }
        if (!acceptance.isEmpty() && evidence.isEmpty()) {
            failures.add("acceptance criteria require independent evidence");
        }
        if (!expected.isEmpty() && evidence.isEmpty() && artifacts.isEmpty()) {
            failures.add("expected outputs require evidence or artifact references");
        }
        if (expectsFile(expected) && artifacts.isEmpty()) {
            failures.add("file output requires an immutable artifact reference");
        }
        return new Decision(
                failures.isEmpty(), true, List.copyOf(failures), evidence.size(), artifacts.size());
    }

    private static boolean expectsFile(List<?> expected) {
        for (Object value : expected) {
            String text = string(value).toLowerCase(Locale.ROOT);
            if (text.contains("file")
                    || text.contains("report")
                    || text.contains("document")
                    || text.contains("文件")
                    || text.contains("报告")
                    || text.matches(".*\\.[a-z0-9]{1,8}($|\\s).*")) {
                return true;
            }
        }
        return false;
    }

    private static Map<?, ?> jsonObject(String json, String field) {
        Object value = parse(json, field);
        if (!(value instanceof Map<?, ?> map)) {
            throw new VerificationContractException(field + " must be a JSON object");
        }
        return map;
    }

    private static List<?> jsonList(String json, String field) {
        Object value = parse(json, field);
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new VerificationContractException(field + " must be a JSON array");
        }
        return list;
    }

    private static Object parse(String json, String field) {
        String normalized = json == null || json.isBlank() ? "[]" : json;
        try {
            Object value = JsonUtils.getJsonCodec().fromJson(normalized, Object.class);
            if (value instanceof String nested) {
                value = JsonUtils.getJsonCodec().fromJson(nested, Object.class);
            }
            return value;
        } catch (RuntimeException e) {
            throw new VerificationContractException(field + " contains invalid JSON", e);
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    public record Decision(
            boolean passed,
            boolean verificationRequired,
            List<String> failures,
            int evidenceCount,
            int artifactCount) {}

    public static final class VerificationContractException extends RuntimeException {
        public VerificationContractException(String message) {
            super(message);
        }

        public VerificationContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
