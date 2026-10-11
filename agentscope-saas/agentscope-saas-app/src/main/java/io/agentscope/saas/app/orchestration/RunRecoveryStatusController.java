/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.core.tenant.TenantResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class RunRecoveryStatusController {
    private final TenantResolver tenants;
    private final RunRecoveryStatusService status;

    public RunRecoveryStatusController(TenantResolver tenants, RunRecoveryStatusService status) {
        this.tenants = tenants;
        this.status = status;
    }

    @GetMapping("/api/agents/{agentId}/runs/{runId}/recovery")
    public Mono<RunRecoveryStatusService.View> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String runId) {
        var tenant = tenants.resolve(jwt == null ? Map.of() : jwt.getClaims());
        var agent = uuid(agentId);
        var run = uuid(runId);
        return Mono.fromCallable(() -> status.status(tenant, agent, run))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
    }
}
