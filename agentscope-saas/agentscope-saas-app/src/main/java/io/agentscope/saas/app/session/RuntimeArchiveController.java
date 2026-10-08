/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.session;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.PgSessionArchiveStore;
import io.agentscope.saas.core.tenant.TenantResolver;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runtime trace is separate from the user-visible question/answer message API. */
@RestController
public class RuntimeArchiveController {
    private final ChatSessionRepository sessions;
    private final PgSessionArchiveStore archive;
    private final TenantResolver tenants;
    private final SaasProperties properties;

    public RuntimeArchiveController(
            ChatSessionRepository sessions,
            PgSessionArchiveStore archive,
            TenantResolver tenants,
            SaasProperties properties) {
        this.sessions = sessions;
        this.archive = archive;
        this.tenants = tenants;
        this.properties = properties;
    }

    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/runtime-messages")
    public Mono<PgSessionArchiveStore.Page> page(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestParam(required = false) String agentLabel,
            @RequestParam(required = false) String sessionKey,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean includeContent) {
        var tenant = tenants.resolve(jwt == null ? Map.of() : jwt.getClaims());
        UUID agent = uuid(agentId), session = uuid(sessionId);
        if (limit < 1
                || limit > 500
                || (afterSeq != null && afterSeq < 0)
                || (beforeSeq != null && beforeSeq < 1)
                || (afterSeq != null && beforeSeq != null))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid runtime archive window");
        return Mono.fromCallable(
                        () -> {
                            sessions.findByIdAndOrgIdAndUserIdAndAgentId(
                                            session,
                                            UUID.fromString(tenant.orgId()),
                                            UUID.fromString(tenant.userId()),
                                            agent)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Session not found"));
                            return archive.page(
                                    tenant,
                                    agent,
                                    session,
                                    agentLabel == null
                                            ? properties.getAgent().getName()
                                            : agentLabel,
                                    sessionKey == null ? sessionId : sessionKey,
                                    afterSeq,
                                    beforeSeq,
                                    limit,
                                    includeContent);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid runtime archive ID");
        }
    }
}
