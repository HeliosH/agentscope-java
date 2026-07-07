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
package io.agentscope.saas.app.degradation;

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

/** Org-admin diagnostics for runtime dependency degradation policy. */
@RestController
@RequestMapping("/api/admin/degradation")
public class DegradationAdminController {

    private static final String ADMIN_ROLE = "admin";

    private final DegradationManager degradationManager;

    public DegradationAdminController(DegradationManager degradationManager) {
        this.degradationManager = degradationManager;
    }

    @GetMapping
    public Mono<ResponseEntity<DegradationManager.Decision>> status(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean refresh) {
        requireAdmin(jwt);
        return Mono.fromCallable(() -> ResponseEntity.ok(degradationManager.currentStatus(refresh)))
                .subscribeOn(Schedulers.boundedElastic());
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
}
