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
package io.agentscope.saas.app.admin;

import static io.agentscope.saas.app.admin.AdminSecurity.boundLimit;
import static io.agentscope.saas.app.admin.AdminSecurity.normalize;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.parseOptionalUuid;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import io.agentscope.saas.core.persistence.entity.AuditLogEntity;
import io.agentscope.saas.core.persistence.repo.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Org-admin audit log search scoped to the caller's organization. */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final AuditLogRepository repository;

    public AdminAuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record AuditLogView(
            long id,
            String orgId,
            String actor,
            String action,
            String resource,
            String detail,
            OffsetDateTime ts) {}

    @GetMapping
    public Mono<ResponseEntity<List<AuditLogView>>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourcePrefix,
            @RequestParam(required = false) Integer limit) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actorId = parseOptionalUuid("actor", actor);
        String normalizedAction = normalize(action);
        String normalizedResourcePrefix = normalize(resourcePrefix);
        int boundedLimit = boundLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        repository
                                                .findAdminAuditLogs(
                                                        orgId,
                                                        actorId,
                                                        normalizedAction,
                                                        normalizedResourcePrefix,
                                                        PageRequest.of(0, boundedLimit))
                                                .stream()
                                                .map(AdminAuditController::toView)
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static AuditLogView toView(AuditLogEntity entity) {
        return new AuditLogView(
                entity.getId(),
                entity.getOrgId().toString(),
                entity.getActor() != null ? entity.getActor().toString() : null,
                entity.getAction(),
                entity.getResource(),
                entity.getDetail(),
                entity.getTs());
    }
}
