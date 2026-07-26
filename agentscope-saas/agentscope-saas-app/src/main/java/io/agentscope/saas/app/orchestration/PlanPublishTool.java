/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.orchestration.ExecutionPlanService;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.orchestration.TaskComplexityRouter;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Model-facing bridge from a structured plan tool call to the durable planning aggregate. */
public final class PlanPublishTool extends ToolBase {

    public static final String NAME = "plan_publish";

    private final ExecutionPlanService planning;
    private final ObjectMapper objectMapper;

    public PlanPublishTool(ExecutionPlanService planning, ObjectMapper objectMapper) {
        super(
                ToolBase.builder()
                        .name(NAME)
                        .description(
                                "Publish a complete structured execution DAG for the current"
                                    + " durable Run. Use this instead of Markdown as the scheduling"
                                    + " source of truth. The platform validates dependencies,"
                                    + " isolation, budgets, and approval requirements before any"
                                    + " task can run.")
                        .inputSchema(schema())
                        .readOnly(false)
                        .concurrencySafe(false));
        this.planning = planning;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                () -> {
                    RuntimeContext runtime = param.getRuntimeContext();
                    TenantContext tenant = TenantContext.from(runtime);
                    String runId =
                            runtime != null
                                    ? runtime.get(RunOrchestrationService.ATTR_RUN_ID)
                                    : null;
                    String agentId =
                            runtime != null
                                    ? runtime.get(SandboxRuntimeAttributes.ATTR_AGENT_ID)
                                    : null;
                    if (tenant == null || runId == null || agentId == null) {
                        return result(
                                param,
                                "Error: plan_publish requires a tenant-owned durable Run context.");
                    }
                    try {
                        ExecutionPlan plan =
                                objectMapper.convertValue(param.getInput(), ExecutionPlan.class);
                        if (TaskComplexityRouter.Route.APPROVAL_REQUIRED
                                .name()
                                .equals(runtime.get(TaskComplexityRouter.ATTR_ROUTE))) {
                            plan =
                                    new ExecutionPlan(
                                            plan.goal(),
                                            plan.tasks(),
                                            true,
                                            plan.rationale(),
                                            plan.budget());
                        }
                        var published =
                                planning.publish(
                                        tenant,
                                        UUID.fromString(agentId),
                                        UUID.fromString(runId),
                                        plan);
                        return result(
                                param,
                                objectMapper.writeValueAsString(
                                        Map.of(
                                                "status",
                                                published.status(),
                                                "planId",
                                                published.planId().toString(),
                                                "version",
                                                published.version(),
                                                "taskCount",
                                                published.tasks().size(),
                                                "approvalRequired",
                                                published.approvalRequired(),
                                                "reused",
                                                published.reused(),
                                                "nextAction",
                                                published.approvalRequired()
                                                        ? "Wait for the user to decide through the"
                                                                + " Run approval API. Do not call"
                                                                + " other tools."
                                                        : "The durable scheduler owns execution."
                                                                + " Do not call other tools.")));
                    } catch (RuntimeException e) {
                        return result(param, "Error: " + e.getMessage());
                    }
                });
    }

    private static ToolResultBlock result(ToolCallParam param, String text) {
        return ToolResultBlock.text(text)
                .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
    }

    private static Map<String, Object> schema() {
        Map<String, Object> stringArray =
                Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> budget =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "tokenLimit", Map.of("type", "integer", "minimum", 0),
                                "costLimitMicros", Map.of("type", "integer", "minimum", 0),
                                "modelCallLimit", Map.of("type", "integer", "minimum", 0),
                                "durationSeconds", Map.of("type", "integer", "minimum", 0),
                                "sandboxCount", Map.of("type", "integer", "minimum", 0),
                                "storageBytes", Map.of("type", "integer", "minimum", 0)));
        Map<String, Object> task =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.ofEntries(
                                Map.entry("clientTaskId", Map.of("type", "string")),
                                Map.entry("title", Map.of("type", "string")),
                                Map.entry("agentType", Map.of("type", "string")),
                                Map.entry("dependsOn", stringArray),
                                Map.entry("input", Map.of("type", "object")),
                                Map.entry("expectedOutputs", stringArray),
                                Map.entry("acceptanceCriteria", stringArray),
                                Map.entry(
                                        "workspaceMode",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of(
                                                        "NONE",
                                                        "USER_SHARED_READ_ONLY",
                                                        "RUN_ISOLATED",
                                                        "ATTEMPT_ISOLATED",
                                                        "DEDICATED_SANDBOX"))),
                                Map.entry("writeIntent", Map.of("type", "boolean")),
                                Map.entry("approvalRequired", Map.of("type", "boolean")),
                                Map.entry("verificationRequired", Map.of("type", "boolean")),
                                Map.entry("priority", Map.of("type", "integer")),
                                Map.entry("maxAttempts", Map.of("type", "integer", "minimum", 1)),
                                Map.entry(
                                        "retryMode",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of("IDEMPOTENT", "MANUAL"))),
                                Map.entry("budget", budget)),
                        "required",
                        List.of(
                                "clientTaskId",
                                "title",
                                "agentType",
                                "dependsOn",
                                "expectedOutputs",
                                "acceptanceCriteria",
                                "workspaceMode",
                                "maxAttempts"));
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "goal", Map.of("type", "string"),
                        "tasks", Map.of("type", "array", "items", task),
                        "approvalRequired", Map.of("type", "boolean"),
                        "rationale", Map.of("type", "string"),
                        "budget", budget),
                "required",
                List.of("goal", "tasks", "approvalRequired"));
    }
}
