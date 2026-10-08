/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static io.agentscope.saas.app.admin.AdminSecurity.actorId;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.PurposePolicy;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/model-invocations/policies")
public class AdminModelInvocationPoliciesController {
    private final ModelInvocationPolicyService service;

    public AdminModelInvocationPoliciesController(ModelInvocationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<List<PurposePolicy>> list(@AuthenticationPrincipal Jwt jwt) {
        requireOrgAdmin(jwt);
        var org = orgId(jwt);
        return Mono.fromCallable(() -> service.list(org)).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{purpose}")
    public Mono<PurposePolicy> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String purpose,
            @RequestBody ModelInvocationPolicyService.PolicyCommand command) {
        requireOrgAdmin(jwt);
        var org = orgId(jwt);
        var actor = actorId(jwt);
        return Mono.fromCallable(() -> service.update(org, actor, purpose, command))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
