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
package io.agentscope.saas.app.auth;

import io.agentscope.saas.core.persistence.entity.OrgEntity;
import io.agentscope.saas.core.persistence.entity.UserEntity;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Local-login authentication endpoints. {@code POST /api/auth/login} verifies credentials against
 * the {@code users} table and returns a signed JWT carrying tenant claims. {@code GET /api/auth/me}
 * echoes the authenticated principal's claims for the frontend to bootstrap state.
 *
 * <p>When enterprise SSO is configured, the IdP performs login and issues tokens; this controller's
 * {@code login} endpoint is then unused, but {@code me} still works for any valid bearer token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Login request payload. */
    public record LoginRequest(String email, String password) {}

    /** Registration request payload. */
    public record RegisterRequest(String email, String password, String displayName) {}

    /** Login response payload. */
    public record LoginResponse(
            String token, String userId, String orgId, String email, String role, String tier) {}

    /** Slug of the org self-registered users are placed into (seeded by V2__seed.sql). */
    private static final String DEFAULT_ORG_SLUG = "demo";

    private final AuthBootstrapRepository bootstrapRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(
            AuthBootstrapRepository bootstrapRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.bootstrapRepository = bootstrapRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody LoginRequest request) {
        return Mono.fromCallable(() -> doLogin(request)).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Simple self-registration. Creates a member-tier user in the default (demo) org and returns a
     * signed JWT so the frontend can enter the app directly. No email verification — this is the
     * intentionally simple login scheme (no SSO) for the multi-user assistant.
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<Object>> register(@RequestBody RegisterRequest request) {
        return Mono.fromCallable(() -> doRegister(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Object> doRegister(RegisterRequest request) {
        if (request == null
                || request.email() == null
                || request.email().isBlank()
                || request.password() == null
                || request.password().isBlank()) {
            return ResponseEntity.badRequest()
                    .body((Object) Map.of("error", "email and password required"));
        }
        if (bootstrapRepository.findUserByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body((Object) Map.of("error", "email already registered"));
        }
        OrgEntity org =
                bootstrapRepository
                        .findOrgBySlug(DEFAULT_ORG_SLUG)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Default org '"
                                                        + DEFAULT_ORG_SLUG
                                                        + "' not seeded"));
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrgId(org.getId());
        user.setEmail(request.email());
        user.setDisplayName(
                request.displayName() == null || request.displayName().isBlank()
                        ? request.email()
                        : request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("member");
        user.setTier("standard");
        UserEntity saved = bootstrapRepository.saveUser(user);
        String token =
                jwtService.issue(
                        saved.getId().toString(),
                        saved.getOrgId().toString(),
                        saved.getEmail(),
                        saved.getRole(),
                        saved.getTier());
        return ResponseEntity.ok(
                (Object)
                        new LoginResponse(
                                token,
                                saved.getId().toString(),
                                saved.getOrgId().toString(),
                                saved.getEmail(),
                                saved.getRole(),
                                saved.getTier()));
    }

    private ResponseEntity<Object> doLogin(LoginRequest request) {
        if (request == null || request.email() == null || request.password() == null) {
            return ResponseEntity.badRequest()
                    .body((Object) Map.of("error", "email and password required"));
        }
        UserEntity user = bootstrapRepository.findUserByEmail(request.email()).orElse(null);
        if (user == null
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body((Object) Map.of("error", "invalid credentials"));
        }
        String token =
                jwtService.issue(
                        user.getId().toString(),
                        user.getOrgId().toString(),
                        user.getEmail(),
                        user.getRole(),
                        user.getTier());
        return ResponseEntity.ok(
                (Object)
                        new LoginResponse(
                                token,
                                user.getId().toString(),
                                user.getOrgId().toString(),
                                user.getEmail(),
                                user.getRole(),
                                user.getTier()));
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<Object>> me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return Mono.just(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body((Object) Map.of("error", "unauthenticated")));
        }
        Object body =
                Map.of(
                        "userId", jwt.getClaimAsString("user_id"),
                        "orgId", jwt.getClaimAsString("org_id"),
                        "email", String.valueOf(jwt.getClaimAsString("email")),
                        "role", String.valueOf(jwt.getClaimAsString("role")),
                        "tier", String.valueOf(jwt.getClaimAsString("tier")));
        return Mono.just(ResponseEntity.ok(body));
    }
}
