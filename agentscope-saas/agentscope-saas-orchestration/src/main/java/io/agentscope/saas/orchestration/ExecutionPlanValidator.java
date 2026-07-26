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

import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.ResourceBudget;
import io.agentscope.saas.domain.orchestration.ExecutionPlan.TaskSpec;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Side-effect-free validation for model-published execution plans. */
public final class ExecutionPlanValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SECRET_KEY =
            Pattern.compile(
                    "(?i).*(password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key).*");

    private final Limits limits;

    public ExecutionPlanValidator(Limits limits) {
        this.limits = limits != null ? limits : Limits.defaults();
    }

    public ValidatedPlan validate(ExecutionPlan plan) {
        List<Violation> violations = new ArrayList<>();
        if (plan == null) {
            throw new InvalidPlanException(List.of(new Violation("plan", "must be provided")));
        }
        if (blank(plan.goal())) {
            violations.add(new Violation("goal", "must contain a goal"));
        } else if (plan.goal().length() > limits.maxGoalLength()) {
            violations.add(new Violation("goal", "exceeds maximum length"));
        }
        if (plan.tasks().isEmpty()) {
            violations.add(new Violation("tasks", "must contain at least one task"));
        }
        if (plan.tasks().size() > limits.maxTasks()) {
            violations.add(new Violation("tasks", "exceeds task quota " + limits.maxTasks()));
        }

        Map<String, TaskSpec> tasks = new LinkedHashMap<>();
        for (int index = 0; index < plan.tasks().size(); index++) {
            TaskSpec task = plan.tasks().get(index);
            String path = "tasks[" + index + "]";
            validateTask(task, path, violations);
            if (task == null || blank(task.clientTaskId())) {
                continue;
            }
            if (tasks.putIfAbsent(task.clientTaskId(), task) != null) {
                violations.add(
                        new Violation(path + ".clientTaskId", "must be unique within the plan"));
            }
        }

        Map<String, Integer> indegrees = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (String id : tasks.keySet()) {
            indegrees.put(id, 0);
            children.put(id, new ArrayList<>());
        }
        for (TaskSpec task : tasks.values()) {
            Set<String> uniqueDependencies = new HashSet<>();
            for (String dependency : task.dependsOn()) {
                if (!tasks.containsKey(dependency)) {
                    violations.add(
                            new Violation(
                                    "tasks." + task.clientTaskId() + ".dependsOn",
                                    "references unknown task " + dependency));
                    continue;
                }
                if (!uniqueDependencies.add(dependency)) {
                    violations.add(
                            new Violation(
                                    "tasks." + task.clientTaskId() + ".dependsOn",
                                    "contains duplicate dependency " + dependency));
                    continue;
                }
                indegrees.computeIfPresent(task.clientTaskId(), (key, value) -> value + 1);
                children.get(dependency).add(task.clientTaskId());
            }
        }

        GraphMetrics graph = graphMetrics(indegrees, children);
        if (graph.visited() != tasks.size()) {
            violations.add(new Violation("tasks", "dependency graph must be acyclic"));
        }
        if (graph.depth() > limits.maxDepth()) {
            violations.add(new Violation("tasks", "DAG depth exceeds " + limits.maxDepth()));
        }
        if (graph.maxFanout() > limits.maxFanout()) {
            violations.add(new Violation("tasks", "DAG fanout exceeds " + limits.maxFanout()));
        }
        if (graph.maxParallelism() > limits.maxParallelism()) {
            violations.add(
                    new Violation("tasks", "DAG parallelism exceeds " + limits.maxParallelism()));
        }
        validateBudget(plan.budget(), "budget", violations);
        if (!violations.isEmpty()) {
            throw new InvalidPlanException(violations);
        }
        return new ValidatedPlan(plan, graph.depth(), graph.maxFanout(), graph.maxParallelism());
    }

    private void validateTask(TaskSpec task, String path, List<Violation> violations) {
        if (task == null) {
            violations.add(new Violation(path, "must not be null"));
            return;
        }
        if (blank(task.clientTaskId()) || !IDENTIFIER.matcher(task.clientTaskId()).matches()) {
            violations.add(
                    new Violation(path + ".clientTaskId", "must match " + IDENTIFIER.pattern()));
        }
        if (blank(task.title()) || task.title().length() > limits.maxTitleLength()) {
            violations.add(
                    new Violation(path + ".title", "must contain 1-" + limits.maxTitleLength()));
        }
        if (blank(task.agentType()) || !IDENTIFIER.matcher(task.agentType()).matches()) {
            violations.add(new Violation(path + ".agentType", "is invalid"));
        }
        if (!limits.allowedAgentTypes().isEmpty()
                && !limits.allowedAgentTypes().contains(task.agentType())) {
            violations.add(new Violation(path + ".agentType", "is not allowed"));
        }
        if (task.maxAttempts() < 1 || task.maxAttempts() > limits.maxAttempts()) {
            violations.add(
                    new Violation(
                            path + ".maxAttempts",
                            "must be between 1 and " + limits.maxAttempts()));
        }
        String retryMode = task.retryMode().toUpperCase(Locale.ROOT);
        if (!Set.of("IDEMPOTENT", "MANUAL").contains(retryMode)) {
            violations.add(new Violation(path + ".retryMode", "must be IDEMPOTENT or MANUAL"));
        }
        if (task.writeIntent()
                && task.workspaceMode() != WorkspaceIsolationMode.RUN_ISOLATED
                && task.workspaceMode() != WorkspaceIsolationMode.ATTEMPT_ISOLATED
                && task.workspaceMode() != WorkspaceIsolationMode.DEDICATED_SANDBOX) {
            violations.add(
                    new Violation(
                            path + ".workspaceMode", "write tasks require an isolated workspace"));
        }
        if ((task.writeIntent() || task.approvalRequired())
                && task.acceptanceCriteria().isEmpty()) {
            violations.add(
                    new Violation(
                            path + ".acceptanceCriteria",
                            "write and approval tasks require acceptance criteria"));
        }
        if (containsSecret(task.input())) {
            violations.add(
                    new Violation(path + ".input", "must not contain credentials or secrets"));
        }
        if (estimateSize(task.input()) > limits.maxInputCharacters()) {
            violations.add(
                    new Violation(
                            path + ".input",
                            "exceeds input character budget " + limits.maxInputCharacters()));
        }
        validateBudget(task.budget(), path + ".budget", violations);
    }

    private void validateBudget(ResourceBudget budget, String path, List<Violation> violations) {
        validateLimit(budget.tokenLimit(), limits.maxTokens(), path + ".tokenLimit", violations);
        validateLimit(
                budget.costLimitMicros(),
                limits.maxCostMicros(),
                path + ".costLimitMicros",
                violations);
        validateLimit(
                budget.modelCallLimit(),
                limits.maxModelCalls(),
                path + ".modelCallLimit",
                violations);
        validateLimit(
                budget.durationSeconds(),
                limits.maxDurationSeconds(),
                path + ".durationSeconds",
                violations);
        validateLimit(
                budget.sandboxCount(), limits.maxSandboxes(), path + ".sandboxCount", violations);
        validateLimit(
                budget.storageBytes(),
                limits.maxStorageBytes(),
                path + ".storageBytes",
                violations);
    }

    private static void validateLimit(
            Number value, long maximum, String path, List<Violation> violations) {
        if (value == null) {
            return;
        }
        long amount = value.longValue();
        if (amount < 0 || (maximum > 0 && amount > maximum)) {
            violations.add(new Violation(path, "must be non-negative and not exceed " + maximum));
        }
    }

    private static GraphMetrics graphMetrics(
            Map<String, Integer> inputDegrees, Map<String, List<String>> children) {
        Map<String, Integer> indegrees = new HashMap<>(inputDegrees);
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegrees.forEach(
                (task, degree) -> {
                    if (degree == 0) {
                        ready.add(task);
                    }
                });
        int visited = 0;
        int depth = 0;
        int maximumParallelism = ready.size();
        int maximumFanout = children.values().stream().mapToInt(List::size).max().orElse(0);
        while (!ready.isEmpty()) {
            int levelSize = ready.size();
            maximumParallelism = Math.max(maximumParallelism, levelSize);
            depth++;
            for (int index = 0; index < levelSize; index++) {
                String task = ready.remove();
                visited++;
                for (String child : children.getOrDefault(task, List.of())) {
                    int degree = indegrees.computeIfPresent(child, (key, value) -> value - 1);
                    if (degree == 0) {
                        ready.add(child);
                    }
                }
            }
        }
        return new GraphMetrics(visited, depth, maximumFanout, maximumParallelism);
    }

    private static boolean containsSecret(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (SECRET_KEY.matcher(String.valueOf(entry.getKey())).matches()
                        || containsSecret(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsSecret(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int estimateSize(Object value) {
        return value != null ? value.toString().length() : 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record GraphMetrics(int visited, int depth, int maxFanout, int maxParallelism) {}

    public record ValidatedPlan(ExecutionPlan plan, int depth, int maxFanout, int maxParallelism) {}

    public record Violation(String path, String message) {}

    public record Limits(
            int maxTasks,
            int maxDepth,
            int maxFanout,
            int maxParallelism,
            int maxAttempts,
            int maxGoalLength,
            int maxTitleLength,
            int maxInputCharacters,
            long maxTokens,
            long maxCostMicros,
            int maxModelCalls,
            long maxDurationSeconds,
            int maxSandboxes,
            long maxStorageBytes,
            Set<String> allowedAgentTypes) {

        public Limits {
            allowedAgentTypes =
                    allowedAgentTypes != null ? Set.copyOf(allowedAgentTypes) : Set.of();
        }

        public static Limits defaults() {
            return new Limits(
                    32,
                    8,
                    8,
                    8,
                    5,
                    2000,
                    500,
                    32_000,
                    200_000,
                    0,
                    200,
                    3_600,
                    8,
                    10L * 1024 * 1024 * 1024,
                    Set.of());
        }
    }

    public static final class InvalidPlanException extends IllegalArgumentException {

        private final List<Violation> violations;

        public InvalidPlanException(List<Violation> violations) {
            super(
                    "Execution plan is invalid: "
                            + violations.stream()
                                    .map(v -> v.path() + " " + v.message())
                                    .reduce((left, right) -> left + "; " + right)
                                    .orElse("unknown validation error"));
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() {
            return violations;
        }
    }
}
