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

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import io.agentscope.saas.domain.orchestration.ExecutionPlan;
import io.agentscope.saas.orchestration.ExecutionPlanService;
import io.agentscope.saas.orchestration.ExecutionPlanService.ApprovalResult;
import io.agentscope.saas.orchestration.ExecutionPlanService.PlanNotFoundException;
import io.agentscope.saas.orchestration.ExecutionPlanService.PlanView;
import io.agentscope.saas.orchestration.ExecutionPlanService.RunNotFoundException;
import io.agentscope.saas.orchestration.ExecutionPlanValidator.InvalidPlanException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-owned API for plan publication, revision, retrieval, and approval. */
@RestController
public class RunPlanningController {

    public record ApprovalRequest(UUID planId, String decision, String reason) {}

    private final TenantResolver tenantResolver;
    private final ExecutionPlanService planning;
    private final boolean enabled;

    public RunPlanningController(
            TenantResolver tenantResolver,
            ExecutionPlanService planning,
            SaasProperties properties) {
        this.tenantResolver = tenantResolver;
        this.planning = planning;
        this.enabled =
                properties != null
                        && properties.getOrchestration() != null
                        && properties.getOrchestration().isEnabled()
                        && properties.getOrchestration().isPlannerEnabled();
    }

    @PostMapping("/api/agents/{agentId}/runs/{runId}/plans")
    public PlanView publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId,
            @RequestBody ExecutionPlan plan) {
        ensureEnabled();
        TenantContext tenant = tenant(jwt);
        try {
            return planning.publish(tenant, uuid(agentId, "agentId"), uuid(runId, "runId"), plan);
        } catch (InvalidPlanException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (RunNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found", e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/plan")
    public PlanView latest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        ensureEnabled();
        TenantContext tenant = tenant(jwt);
        return planning.latest(tenant, uuid(agentId, "agentId"), uuid(runId, "runId"))
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
    }

    @PostMapping("/api/agents/{agentId}/runs/{runId}/approve")
    public ApprovalResult approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ApprovalRequest request) {
        ensureEnabled();
        if (request == null || request.planId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planId is required");
        }
        TenantContext tenant = tenant(jwt);
        try {
            return planning.decide(
                    tenant,
                    uuid(agentId, "agentId"),
                    uuid(runId, "runId"),
                    request.planId(),
                    request.decision(),
                    request.reason(),
                    idempotencyKey);
        } catch (RunNotFoundException | PlanNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    private TenantContext tenant(Jwt jwt) {
        TenantContext tenant = tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of());
        uuid(tenant.orgId(), "orgId");
        uuid(tenant.userId(), "userId");
        return tenant;
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Structured planning is disabled");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID", e);
        }
    }
}
