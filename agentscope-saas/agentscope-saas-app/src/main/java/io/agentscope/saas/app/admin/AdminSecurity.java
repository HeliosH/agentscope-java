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

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Shared JWT/parameter checks for org-admin and platform-admin endpoints. */
public final class AdminSecurity {

    public static final String ADMIN_ROLE = "admin";
    public static final String PLATFORM_ADMIN_ROLE = "platform_admin";

    private AdminSecurity() {}

    public static void requireOrgAdmin(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        String role = jwt.getClaimAsString("role");
        if (!ADMIN_ROLE.equals(role) && !PLATFORM_ADMIN_ROLE.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
    }

    public static void requirePlatformAdmin(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        String role = jwt.getClaimAsString("role");
        if (!PLATFORM_ADMIN_ROLE.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform_admin role required");
        }
    }

    public static UUID orgId(Jwt jwt) {
        return parseRequiredUuid("org_id", jwt != null ? jwt.getClaimAsString("org_id") : null);
    }

    public static UUID actorId(Jwt jwt) {
        String subject = normalize(jwt != null ? jwt.getSubject() : null);
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static UUID parseRequiredUuid(String name, String value) {
        UUID parsed = parseOptionalUuid(name, value);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid " + name);
        }
        return parsed;
    }

    public static UUID parseOptionalUuid(String name, String value) {
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

    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static int boundLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }
}
