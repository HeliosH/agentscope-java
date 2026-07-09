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

import static io.agentscope.saas.app.admin.AdminSecurity.actorId;
import static io.agentscope.saas.app.admin.AdminSecurity.normalize;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;
import static io.agentscope.saas.app.admin.AdminSecurity.requirePlatformAdmin;

import io.agentscope.saas.core.persistence.entity.TierPolicyEntity;
import io.agentscope.saas.core.persistence.repo.TierPolicyRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Quota tier policy view for org admins and write path for platform admins. */
@RestController
@RequestMapping("/api/admin/tier-policies")
public class AdminTierPoliciesController {

    private final TierPolicyRepository repository;
    private final AuditService audit;

    public AdminTierPoliciesController(TierPolicyRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record TierPolicyView(
            String tier,
            Integer maxAgents,
            Integer maxSandboxes,
            Long monthlyTokenQuota,
            Integer storageGb,
            Integer idleTtlSeconds) {}

    public record TierPolicyRequest(
            Integer maxAgents,
            Integer maxSandboxes,
            Long monthlyTokenQuota,
            Integer storageGb,
            Integer idleTtlSeconds) {}

    @GetMapping
    public Mono<ResponseEntity<List<TierPolicyView>>> list(@AuthenticationPrincipal Jwt jwt) {
        requireOrgAdmin(jwt);
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        repository.findAll().stream()
                                                .sorted(
                                                        Comparator.comparing(
                                                                TierPolicyEntity::getTier))
                                                .map(AdminTierPoliciesController::toView)
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{tier}")
    public Mono<ResponseEntity<TierPolicyView>> upsert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tier,
            @RequestBody TierPolicyRequest request) {
        requirePlatformAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actor = actorId(jwt);
        String normalizedTier = normalize(tier);
        validateTier(normalizedTier);
        return Mono.fromCallable(
                        () -> {
                            TierPolicyEntity entity =
                                    repository
                                            .findById(normalizedTier)
                                            .orElseGet(
                                                    () -> {
                                                        TierPolicyEntity created =
                                                                new TierPolicyEntity();
                                                        created.setTier(normalizedTier);
                                                        return created;
                                                    });
                            apply(entity, request);
                            TierPolicyEntity saved = repository.save(entity);
                            audit.record(
                                    orgId,
                                    actor,
                                    "admin.tier_policy.upsert",
                                    "tier:" + normalizedTier,
                                    Map.of("tier", normalizedTier));
                            return ResponseEntity.ok(toView(saved));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void apply(TierPolicyEntity entity, TierPolicyRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        entity.setMaxAgents(nonNegative("maxAgents", request.maxAgents()));
        entity.setMaxSandboxes(nonNegative("maxSandboxes", request.maxSandboxes()));
        entity.setMonthlyTokenQuota(nonNegative("monthlyTokenQuota", request.monthlyTokenQuota()));
        entity.setStorageGb(nonNegative("storageGb", request.storageGb()));
        entity.setIdleTtlSeconds(nonNegative("idleTtlSeconds", request.idleTtlSeconds()));
    }

    private static Integer nonNegative(String name, Integer value) {
        if (value != null && value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be >= 0");
        }
        return value;
    }

    private static Long nonNegative(String name, Long value) {
        if (value != null && value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be >= 0");
        }
        return value;
    }

    private static void validateTier(String tier) {
        if (tier == null || !tier.matches("[a-zA-Z0-9_.-]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid tier");
        }
    }

    private static TierPolicyView toView(TierPolicyEntity entity) {
        return new TierPolicyView(
                entity.getTier(),
                entity.getMaxAgents(),
                entity.getMaxSandboxes(),
                entity.getMonthlyTokenQuota(),
                entity.getStorageGb(),
                entity.getIdleTtlSeconds());
    }
}
