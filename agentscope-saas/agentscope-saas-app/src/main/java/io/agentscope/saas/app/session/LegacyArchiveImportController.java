/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.session;

import io.agentscope.saas.app.admin.AdminSecurity;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.memory.LegacyArchiveImportService;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Mapping;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Owner;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Operator-only conversion; organization is never accepted from request data. */
@RestController
@RequestMapping("/api/admin/agents/{agentId}/sessions/{sessionId}/legacy-archive-imports")
public class LegacyArchiveImportController {
    private final LegacyArchiveImportService service;
    private final AuditService audit;

    public LegacyArchiveImportController(LegacyArchiveImportService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    public record CreateRequest(UUID userId, UUID sourceVersionId) {}

    public record AdvanceRequest(Long expectedOffset, Integer maxBatches) {
        public AdvanceRequest(Long expectedOffset) {
            this(expectedOffset, null);
        }
    }

    @PostMapping
    public Mono<Job> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID agentId,
            @PathVariable UUID sessionId,
            @RequestBody CreateRequest body) {
        Owner owner = owner(jwt, body.userId(), agentId, sessionId);
        if (body.sourceVersionId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceVersionId required");
        return Mono.fromCallable(
                        () -> {
                            Job job = service.create(owner, body.sourceVersionId());
                            audit.record(
                                    owner.orgId(),
                                    AdminSecurity.actorId(jwt),
                                    "legacy_archive_import.create",
                                    job.id().toString(),
                                    Map.of(
                                            "userId",
                                            owner.userId().toString(),
                                            "sourceVersionId",
                                            job.sourceVersionId().toString()));
                            return job;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<Job> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID agentId,
            @PathVariable UUID sessionId,
            @PathVariable UUID id,
            @RequestParam UUID userId) {
        Owner owner = owner(jwt, userId, agentId, sessionId);
        return Mono.fromCallable(() -> service.get(owner, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/advance")
    public Mono<Job> advance(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID agentId,
            @PathVariable UUID sessionId,
            @PathVariable UUID id,
            @RequestParam UUID userId,
            @RequestBody AdvanceRequest body) {
        Owner owner = owner(jwt, userId, agentId, sessionId);
        if (body.expectedOffset() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedOffset required");
        return Mono.fromCallable(
                        () -> {
                            Job job =
                                    service.advance(
                                            owner,
                                            id,
                                            body.expectedOffset(),
                                            body.maxBatches() == null ? 1 : body.maxBatches());
                            audit.record(
                                    owner.orgId(),
                                    AdminSecurity.actorId(jwt),
                                    "legacy_archive_import.advance",
                                    job.id().toString(),
                                    Map.of(
                                            "byteOffset",
                                            job.byteOffset(),
                                            "lineNo",
                                            job.lineNo(),
                                            "status",
                                            job.status()));
                            return job;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/entries")
    public Mono<List<Mapping>> entries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID agentId,
            @PathVariable UUID sessionId,
            @PathVariable UUID id,
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") long afterLine,
            @RequestParam(defaultValue = "100") int limit) {
        Owner owner = owner(jwt, userId, agentId, sessionId);
        return Mono.fromCallable(() -> service.mappings(owner, id, afterLine, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Owner owner(Jwt jwt, UUID user, UUID agent, UUID session) {
        AdminSecurity.requireOrgAdmin(jwt);
        if (user == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        return new Owner(AdminSecurity.orgId(jwt), user, agent, session);
    }
}
