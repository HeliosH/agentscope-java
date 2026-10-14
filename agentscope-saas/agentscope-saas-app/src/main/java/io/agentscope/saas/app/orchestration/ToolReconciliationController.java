/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class ToolReconciliationController {
    private final ToolReconciliationService service;
    private final TenantResolver tenants;

    public ToolReconciliationController(ToolReconciliationService service, TenantResolver tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/operations")
    public Mono<ToolReconciliationService.Page> owned(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit) {
        var tenant = tenant(jwt);
        var agent = uuid(agentId);
        var run = uuid(runId);
        var cursor = after == null || after.isBlank() ? null : uuid(after);
        return Mono.fromCallable(() -> service.ownedOperations(tenant, agent, run, cursor, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/admin/tool-operations")
    public Mono<ToolReconciliationService.Page> organization(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "OUTCOME_UNKNOWN") String state,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit) {
        requireOrgAdmin(jwt);
        var tenant = tenant(jwt);
        var cursor = after == null || after.isBlank() ? null : uuid(after);
        return Mono.fromCallable(() -> service.organizationOperations(tenant, state, cursor, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/admin/runs/{runId}/operations/{operationId}/resolutions")
    public Mono<List<ToolReconciliationService.EvidenceView>> history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String runId,
            @PathVariable String operationId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "25") int limit) {
        requireOrgAdmin(jwt);
        var tenant = tenant(jwt);
        var run = uuid(runId);
        var operation = uuid(operationId);
        return Mono.fromCallable(() -> service.history(tenant, run, operation, after, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api/admin/runs/{runId}/operations/{operationId}/reconcile")
    public Mono<ToolReconciliationService.Result> reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String runId,
            @PathVariable String operationId,
            @RequestHeader("Idempotency-Key") String commandKey,
            @RequestBody ToolReconciliationService.Request request) {
        requireOrgAdmin(jwt);
        var tenant = tenant(jwt);
        var run = uuid(runId);
        var operation = uuid(operationId);
        return Mono.fromCallable(
                        () -> service.reconcile(tenant, run, operation, commandKey, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TenantContext tenant(Jwt jwt) {
        return tenants.resolve(jwt == null ? Map.of() : jwt.getClaims());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not found");
        }
    }
}
