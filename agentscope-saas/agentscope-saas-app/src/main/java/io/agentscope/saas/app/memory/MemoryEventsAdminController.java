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
package io.agentscope.saas.app.memory;

import io.agentscope.saas.core.persistence.entity.MemoryEventEntity;
import io.agentscope.saas.core.persistence.repo.MemoryEventRepository;
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
 * Org-admin view over the durable memory event ledger. The org scope is taken only from the caller's
 * JWT, so an admin can inspect replay/sync status for their own tenant without gaining a cross-org
 * query primitive.
 */
@RestController
@RequestMapping("/api/admin/memory-events")
public class MemoryEventsAdminController {

    private static final String ADMIN_ROLE = "admin";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final MemoryEventRepository repository;

    public MemoryEventsAdminController(MemoryEventRepository repository) {
        this.repository = repository;
    }

    public record MemoryEventView(
            String id,
            String orgId,
            String userId,
            String agentId,
            String sessionId,
            String source,
            String eventType,
            String syncStatus,
            int syncAttempts,
            String lastError,
            OffsetDateTime syncedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String contentJson,
            String metadataJson) {}

    @GetMapping
    public Mono<ResponseEntity<List<MemoryEventView>>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String syncStatus,
            @RequestParam(required = false) Integer limit) {
        requireAdmin(jwt);
        UUID orgId = parseRequiredUuid("org_id", jwt.getClaimAsString("org_id"));
        UUID parsedUserId = parseOptionalUuid("userId", userId);
        String normalizedSessionId = normalize(sessionId);
        String normalizedStatus = normalize(syncStatus);
        int boundedLimit = boundLimit(limit);
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        repository
                                                .findAdminEvents(
                                                        orgId,
                                                        parsedUserId,
                                                        normalizedSessionId,
                                                        normalizedStatus,
                                                        PageRequest.of(0, boundedLimit))
                                                .stream()
                                                .map(MemoryEventsAdminController::toView)
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static MemoryEventView toView(MemoryEventEntity entity) {
        return new MemoryEventView(
                entity.getId().toString(),
                entity.getOrgId().toString(),
                entity.getUserId().toString(),
                entity.getAgentId(),
                entity.getSessionId(),
                entity.getSource(),
                entity.getEventType(),
                entity.getSyncStatus(),
                entity.getSyncAttempts(),
                entity.getLastError(),
                entity.getSyncedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getContentJson(),
                entity.getMetadataJson());
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
