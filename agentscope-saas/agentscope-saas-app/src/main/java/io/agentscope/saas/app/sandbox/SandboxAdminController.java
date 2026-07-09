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

import io.agentscope.saas.app.admin.AdminSecurity;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxBroker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Org-admin sandbox inventory. The org scope is always taken from the caller's JWT, never from a
 * request parameter, so admins can inspect runtime resources only inside their own tenant.
 */
@RestController
@RequestMapping("/api/admin/sandboxes")
public class SandboxAdminController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final SandboxRepository repository;
    private final SandboxBroker broker;
    private final SandboxBackendTerminator terminator;
    private final AuditService audit;

    public SandboxAdminController(
            SandboxRepository repository,
            SandboxBroker broker,
            SandboxBackendTerminator terminator) {
        this(repository, broker, terminator, null);
    }

    @Autowired
    public SandboxAdminController(
            SandboxRepository repository,
            SandboxBroker broker,
            SandboxBackendTerminator terminator,
            AuditService audit) {
        this.repository = repository;
        this.broker = broker;
        this.terminator = terminator != null ? terminator : SandboxBackendTerminator.unsupported();
        this.audit = audit;
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
            String backendReleaseStatus,
            int backendReleaseAttempts,
            OffsetDateTime backendReleasedAt,
            String backendReleaseError,
            boolean expired) {}

    public record ForceEvictRequest(String reason, Boolean terminateBackend) {
        public ForceEvictRequest(String reason) {
            this(reason, null);
        }
    }

    public record SandboxActionView(
            String id,
            String orgId,
            String userId,
            String sandboxType,
            String externalId,
            String previousStatus,
            String status,
            boolean changed,
            String backendTerminationStatus,
            String backendTerminationMessage) {}

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

    @PostMapping("/{sandboxId}/force-evict")
    public Mono<ResponseEntity<SandboxActionView>> forceEvict(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sandboxId,
            @RequestBody(required = false) ForceEvictRequest request) {
        requireAdmin(jwt);
        UUID orgId = parseRequiredUuid("org_id", jwt.getClaimAsString("org_id"));
        UUID parsedSandboxId = parseRequiredUuid("sandboxId", sandboxId);
        String reason = request != null ? normalize(request.reason()) : null;
        boolean terminateBackend =
                request == null || request.terminateBackend() == null || request.terminateBackend();
        return Mono.fromCallable(
                        () ->
                                broker.forceEvict(orgId, parsedSandboxId, reason)
                                        .map(
                                                result -> {
                                                    SandboxBackendTerminator.TerminationResult
                                                            termination =
                                                                    terminateBackend(
                                                                            result,
                                                                            terminateBackend);
                                                    broker.recordBackendRelease(
                                                            result.sandbox().getId(), termination);
                                                    recordAudit(
                                                            jwt,
                                                            orgId,
                                                            result,
                                                            reason,
                                                            terminateBackend,
                                                            termination);
                                                    return ResponseEntity.ok(
                                                            toActionView(result, termination));
                                                })
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "sandbox not found")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void recordAudit(
            Jwt jwt,
            UUID orgId,
            SandboxBroker.ForceEvictResult result,
            String reason,
            boolean terminateBackend,
            SandboxBackendTerminator.TerminationResult termination) {
        if (audit == null) {
            return;
        }
        SandboxEntity sandbox = result.sandbox();
        audit.record(
                orgId,
                AdminSecurity.actorId(jwt),
                "admin.sandbox.force_evict",
                "sandbox:" + sandbox.getId(),
                Map.of(
                        "sandboxType",
                        value(sandbox.getSandboxType()),
                        "externalId",
                        value(sandbox.getExternalId()),
                        "previousStatus",
                        value(result.previousStatus()),
                        "status",
                        value(sandbox.getStatus()),
                        "reason",
                        value(reason),
                        "terminateBackend",
                        terminateBackend,
                        "backendTerminationStatus",
                        value(termination.status())));
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
                entity.getBackendReleaseStatus(),
                entity.getBackendReleaseAttempts(),
                entity.getBackendReleasedAt(),
                entity.getBackendReleaseError(),
                expired);
    }

    private SandboxBackendTerminator.TerminationResult terminateBackend(
            SandboxBroker.ForceEvictResult result, boolean enabled) {
        if (!enabled) {
            return SandboxBackendTerminator.TerminationResult.skipped("disabled by request");
        }
        SandboxEntity entity = result.sandbox();
        return terminator.terminate(entity.getSandboxType(), entity.getExternalId());
    }

    private static SandboxActionView toActionView(
            SandboxBroker.ForceEvictResult result,
            SandboxBackendTerminator.TerminationResult termination) {
        SandboxEntity entity = result.sandbox();
        return new SandboxActionView(
                entity.getId().toString(),
                entity.getOrgId().toString(),
                entity.getUserId().toString(),
                entity.getSandboxType(),
                entity.getExternalId(),
                result.previousStatus(),
                entity.getStatus(),
                result.changed(),
                termination.status(),
                termination.message());
    }

    private static void requireAdmin(Jwt jwt) {
        AdminSecurity.requireOrgAdmin(jwt);
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

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static int boundLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
