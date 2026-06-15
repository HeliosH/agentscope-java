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
package io.agentscope.saas.app.session;

import io.agentscope.saas.app.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.app.persistence.repo.ChatSessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org/user-scoped chat session listing. Sessions are created lazily by the chat endpoint; this
 * controller exposes the inbox for the authenticated user within their tenant.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    /** Session view returned to clients. */
    public record SessionView(
            String id, String agentId, String title, Integer messageCount, String source) {}

    private final ChatSessionRepository sessionRepository;

    public SessionController(ChatSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SessionView>>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        return Mono.fromCallable(
                        () ->
                                sessionRepository
                                        .findByOrgIdAndUserIdOrderByUpdatedAtDesc(orgId, userId)
                                        .stream()
                                        .map(SessionController::toView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    private static SessionView toView(ChatSessionEntity e) {
        return new SessionView(
                e.getId().toString(),
                e.getAgentId() == null ? null : e.getAgentId().toString(),
                e.getTitle(),
                e.getMessageCount(),
                e.getSource());
    }
}
