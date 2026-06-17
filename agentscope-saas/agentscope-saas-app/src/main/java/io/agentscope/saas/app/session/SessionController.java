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

import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org/user-scoped chat session listing and history replay. Sessions are created lazily by the chat
 * endpoint; this controller exposes the inbox for the authenticated user within their tenant, plus
 * the message history of a session for replay after refresh/re-login.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    /** Session view returned to clients. */
    public record SessionView(
            String id, String agentId, String title, Integer messageCount, String source) {}

    /** Message view returned to clients for history replay. */
    public record MessageView(String id, String role, String content, String createdAt) {}

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public SessionController(
            ChatSessionRepository sessionRepository, ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
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

    /**
     * Returns the ordered message history of a session. The session must belong to the caller
     * (org + user), otherwise 404 — users cannot read another user's history.
     */
    @GetMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<List<MessageView>>> messages(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        UUID sessionUuid;
        try {
            sessionUuid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    sessionRepository
                                            .findByIdAndOrgIdAndUserId(sessionUuid, orgId, userId)
                                            .orElse(null);
                            if (session == null) {
                                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .<List<MessageView>>build();
                            }
                            List<MessageView> views =
                                    messageRepository
                                            .findBySessionIdOrderByCreatedAtAsc(session.getId())
                                            .stream()
                                            .map(SessionController::toMessageView)
                                            .toList();
                            return ResponseEntity.ok(views);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static SessionView toView(ChatSessionEntity e) {
        return new SessionView(
                e.getId().toString(),
                e.getAgentId() == null ? null : e.getAgentId().toString(),
                e.getTitle(),
                e.getMessageCount(),
                e.getSource());
    }

    private static MessageView toMessageView(ChatMessageEntity e) {
        return new MessageView(
                e.getId().toString(),
                e.getRole(),
                e.getContent(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
