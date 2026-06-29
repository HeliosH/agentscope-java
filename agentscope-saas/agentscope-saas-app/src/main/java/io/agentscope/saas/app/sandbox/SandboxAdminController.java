/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.app.sandbox;

import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org-admin sandbox inventory. The org scope is always taken from the caller's JWT, never from a
 * request parameter, so admins can inspect runtime resources only inside their own tenant.
 */
@RestController
@RequestMapping("/api/admin/sandboxes")
public class SandboxAdminController {

    private static final String ADMIN_ROLE = "admin";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final SandboxRepository repository;

    public SandboxAdminController(SandboxRepository repository) {
        this.repository = repository;
    }

    public record SandboxView(
            String id,
            String orgId,
            String userId,
            String agentId,
            String sessionId,
            String sandboxType,
            String externalId,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime lastUsedAt,
            OffsetDateTime expiresAt,
            boolean expired) {}

    @GetMapping
    public Mono<ResponseEntity<List<SandboxView>>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sandboxType,
            @RequestParam(required = false) Boolean expiredOnly,
            @RequestParam(required = false) Integer limit) {
        requireAdmin(jwt);
        UUID orgId = parseRequiredUuid("org_id", jwt.getClaimAsString("org_id"));
        UUID parsedUserId = parseOptionalUuid("userId", userId);
        String normalizedStatus = normalize(status);
        String normalizedSandboxType = normalize(sandboxType);
        boolean onlyExpired = Boolean.TRUE.equals(expiredOnly);
        int boundedLimit = boundLimit(limit);
        OffsetDateTime now = OffsetDateTime.now();
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        repository
                                                .findAdminSandboxes(
                                                        orgId,
                                                        parsedUserId,
                                                        normalizedStatus,
                                                        normalizedSandboxType,
                                                        onlyExpired,
                                                        now,
                                                        PageRequest.of(0, boundedLimit))
                                                .stream()
                                                .map(entity -> toView(entity, now))
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static SandboxView toView(SandboxEntity entity, OffsetDateTime now) {
        OffsetDateTime expiresAt = entity.getExpiresAt();
        boolean expired =
                "active".equals(entity.getStatus()) && expiresAt != null && expiresAt.isBefore(now);
        return new SandboxView(
                entity.getId().toString(),
                entity.getOrgId().toString(),
                entity.getUserId().toString(),
                entity.getAgentId() != null ? entity.getAgentId().toString() : null,
                entity.getSessionId(),
                entity.getSandboxType(),
                entity.getExternalId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                expiresAt,
                expired);
    }

    private static void requireAdmin(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        String role = jwt.getClaimAsString("role");
        if (!ADMIN_ROLE.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
    }

    private static UUID parseRequiredUuid(String name, String value) {
        UUID parsed = parseOptionalUuid(name, value);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid " + name);
        }
        return parsed;
    }

    private static UUID parseOptionalUuid(String name, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int boundLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
