/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Restores immutable permissions and enforces persistent budgets around every model call. */
public final class OrchestrationGovernanceMiddleware implements MiddlewareBase {

    private final OrchestrationGovernanceService governance;
    private final ObjectMapper objectMapper;

    public OrchestrationGovernanceMiddleware(
            OrchestrationGovernanceService governance, ObjectMapper objectMapper) {
        this.governance = governance;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        Scope scope = scope(ctx);
        if (scope == null) {
            return next.apply(input);
        }
        restorePermissions(agent, ctx, scope);
        require(governance.preflight(scope.orgId(), scope.runId(), scope.agentRunId()));
        Flux<AgentEvent> events =
                next.apply(input)
                        .doOnNext(
                                event -> {
                                    if (event instanceof ModelCallStartEvent) {
                                        require(
                                                governance.preflight(
                                                        scope.orgId(),
                                                        scope.runId(),
                                                        scope.agentRunId()));
                                    } else if (event instanceof ModelCallEndEvent end) {
                                        ChatUsage usage = end.getUsage();
                                        require(
                                                governance.consume(
                                                        scope.orgId(),
                                                        scope.runId(),
                                                        scope.agentRunId(),
                                                        usage != null ? usage.getInputTokens() : 0,
                                                        usage != null ? usage.getOutputTokens() : 0,
                                                        usage != null
                                                                ? usage.getTotalTokens()
                                                                : 0));
                                    }
                                });
        Duration remaining =
                governance
                        .remainingTime(scope.orgId(), scope.runId(), scope.agentRunId())
                        .orElse(null);
        if (remaining == null) {
            return events;
        }
        if (remaining.isZero()) {
            require(governance.preflight(scope.orgId(), scope.runId(), scope.agentRunId()));
        }
        return events.timeout(remaining.plusMillis(10))
                .onErrorMap(
                        TimeoutException.class,
                        error ->
                                deadlineError(
                                        scope,
                                        "Agent execution exceeded its persisted deadline",
                                        error));
    }

    private void restorePermissions(Agent agent, RuntimeContext ctx, Scope scope) {
        if (!(agent instanceof ReActAgent react)) {
            return;
        }
        try {
            var snapshot =
                    governance.permissionSnapshot(scope.orgId(), scope.runId(), scope.agentRunId());
            react.setPermissionContext(
                    ctx, objectMapper.readValue(snapshot.json(), PermissionContextState.class));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to restore Agent permission snapshot", e);
        }
    }

    private static Scope scope(RuntimeContext ctx) {
        TenantContext tenant = TenantContext.from(ctx);
        String runId = ctx != null ? ctx.get(RunOrchestrationService.ATTR_RUN_ID) : null;
        String agentRunId = ctx != null ? ctx.get(RunOrchestrationService.ATTR_AGENT_RUN_ID) : null;
        if (tenant == null || tenant.orgId() == null || runId == null || agentRunId == null) {
            return null;
        }
        try {
            return new Scope(
                    UUID.fromString(tenant.orgId()),
                    UUID.fromString(runId),
                    UUID.fromString(agentRunId));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid orchestration governance scope", e);
        }
    }

    private static void require(OrchestrationGovernanceService.BudgetDecision decision) {
        if (!decision.permitted()) {
            throw new BudgetExceededException(decision.reason(), decision.message());
        }
    }

    private RuntimeException deadlineError(Scope scope, String fallback, Throwable cause) {
        var decision = governance.preflight(scope.orgId(), scope.runId(), scope.agentRunId());
        if (!decision.permitted()) {
            return new BudgetExceededException(decision.reason(), decision.message());
        }
        return new IllegalStateException(fallback, cause);
    }

    private record Scope(UUID orgId, UUID runId, UUID agentRunId) {}

    public static final class BudgetExceededException extends RuntimeException {
        private final String reason;

        BudgetExceededException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
