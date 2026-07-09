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

import static io.agentscope.saas.app.admin.AdminSecurity.PLATFORM_ADMIN_ROLE;
import static io.agentscope.saas.app.admin.AdminSecurity.actorId;
import static io.agentscope.saas.app.admin.AdminSecurity.boundLimit;
import static io.agentscope.saas.app.admin.AdminSecurity.normalize;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.parseRequiredUuid;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import io.agentscope.saas.core.persistence.entity.UserEntity;
import io.agentscope.saas.core.persistence.repo.TierPolicyRepository;
import io.agentscope.saas.core.persistence.repo.UserRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Org-admin user, role, and tier management scoped to the caller's organization. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> VALID_ROLES = Set.of("member", "admin");

    private final UserRepository users;
    private final TierPolicyRepository tierPolicies;
    private final AuditService audit;

    public AdminUsersController(
            UserRepository users, TierPolicyRepository tierPolicies, AuditService audit) {
        this.users = users;
        this.tierPolicies = tierPolicies;
        this.audit = audit;
    }

    public record UserView(
            String id,
            String orgId,
            String email,
            String displayName,
            String role,
            String tier,
            OffsetDateTime createdAt) {}

    public record UpdateUserRequest(String displayName, String role, String tier) {}

    @GetMapping
    public Mono<ResponseEntity<List<UserView>>> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Integer limit) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        int boundedLimit = boundLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        users
                                                .findByOrgIdOrderByCreatedAtDesc(
                                                        orgId, PageRequest.of(0, boundedLimit))
                                                .stream()
                                                .map(AdminUsersController::toView)
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{userId}")
    @Transactional
    public Mono<ResponseEntity<UserView>> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @RequestBody UpdateUserRequest request) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID actor = actorId(jwt);
        String actorRole = jwt.getClaimAsString("role");
        UUID targetUserId = parseRequiredUuid("userId", userId);
        return Mono.fromCallable(
                        () -> {
                            UserEntity user =
                                    users.findByOrgIdAndId(orgId, targetUserId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "user not found"));
                            Map<String, Object> changes =
                                    applyUpdate(orgId, actor, actorRole, user, request);
                            UserEntity saved = users.save(user);
                            if (!changes.isEmpty()) {
                                audit.record(
                                        orgId,
                                        actor,
                                        "admin.user.update",
                                        "user:" + saved.getId(),
                                        changes);
                            }
                            return ResponseEntity.ok(toView(saved));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> applyUpdate(
            UUID orgId, UUID actor, String actorRole, UserEntity user, UpdateUserRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        guardPlatformAdminUser(actorRole, user, request);
        Map<String, Object> changes = new LinkedHashMap<>();
        String displayName = normalize(request.displayName());
        if (request.displayName() != null && !same(user.getDisplayName(), displayName)) {
            user.setDisplayName(trim(displayName, 255));
            changes.put("displayName", user.getDisplayName());
        }
        String role = normalizeRole(request.role());
        if (role != null && !same(user.getRole(), role)) {
            validateRoleChange(orgId, actor, user, role);
            changes.put("role", Map.of("from", value(user.getRole()), "to", role));
            user.setRole(role);
        }
        String tier = normalize(request.tier());
        if (tier != null && !same(user.getTier(), tier)) {
            if (!tierPolicies.existsById(tier)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tier: " + tier);
            }
            changes.put("tier", Map.of("from", value(user.getTier()), "to", tier));
            user.setTier(tier);
        }
        return changes;
    }

    private static void guardPlatformAdminUser(
            String actorRole, UserEntity user, UpdateUserRequest request) {
        if (!PLATFORM_ADMIN_ROLE.equals(user.getRole())) {
            return;
        }
        if (!PLATFORM_ADMIN_ROLE.equals(actorRole)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "platform admin user can only be changed by platform admin");
        }
        String requestedRole = normalizeRole(request.role());
        if (requestedRole != null && !PLATFORM_ADMIN_ROLE.equals(requestedRole)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "platform_admin role cannot be changed here");
        }
    }

    private void validateRoleChange(UUID orgId, UUID actor, UserEntity user, String nextRole) {
        if (!VALID_ROLES.contains(nextRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role");
        }
        if (actor != null && actor.equals(user.getId()) && !"admin".equals(nextRole)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "current admin cannot demote self");
        }
        if ("admin".equals(user.getRole()) && !"admin".equals(nextRole)) {
            long admins = users.countByOrgIdAndRole(orgId, "admin");
            if (admins <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "cannot remove the last admin in the organization");
            }
        }
    }

    private static UserView toView(UserEntity user) {
        return new UserView(
                user.getId().toString(),
                user.getOrgId().toString(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getTier(),
                user.getCreatedAt());
    }

    private static String normalizeRole(String role) {
        String normalized = normalize(role);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean same(String a, String b) {
        return value(a).equals(value(b));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
