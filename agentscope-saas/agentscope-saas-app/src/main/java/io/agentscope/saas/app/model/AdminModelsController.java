/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static io.agentscope.saas.app.admin.AdminSecurity.actorId;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Organization administrator API for model CRUD and connectivity tests. */
@RestController
@RequestMapping("/api/admin/models")
public class AdminModelsController {

    private final ModelManagementService service;

    public AdminModelsController(ModelManagementService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<List<ModelManagementService.ModelView>> list(@AuthenticationPrincipal Jwt jwt) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(() -> service.list(orgId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ModelManagementService.ModelView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ModelManagementService.ModelCommand command) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actorId = actorId(jwt);
        return Mono.fromCallable(() -> service.create(orgId, actorId, command))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{modelId}")
    public Mono<ModelManagementService.ModelView> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String modelId,
            @RequestBody ModelManagementService.ModelCommand command) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actorId = actorId(jwt);
        return Mono.fromCallable(() -> service.update(orgId, actorId, modelId, command))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{modelId}")
    public Mono<ResponseEntity<Void>> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String modelId) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actorId = actorId(jwt);
        return Mono.fromCallable(
                        () -> {
                            service.delete(orgId, actorId, modelId);
                            ResponseEntity<Void> response = ResponseEntity.noContent().build();
                            return response;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{modelId}/test")
    public Mono<ModelManagementService.TestResult> test(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String modelId) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actorId = actorId(jwt);
        return Mono.fromCallable(() -> service.test(orgId, actorId, modelId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
