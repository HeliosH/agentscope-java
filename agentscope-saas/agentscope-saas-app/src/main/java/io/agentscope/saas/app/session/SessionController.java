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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org/user-scoped chat session listing and history replay, nested under the agent resource
 * ({@code /api/agents/{agentId}/sessions}). Sessions are created lazily by the chat endpoint; this
 * controller exposes the inbox for the authenticated user within their tenant, plus the message
 * history of a session for replay after refresh/re-login, plus session deletion.
 *
 * <p>Every query is filtered by {@code (orgId, userId, agentId)} so a user only ever sees sessions
 * for agents they own within their tenant.
 */
@RestController
public class SessionController {

    /** Session view returned to clients. */
    public record SessionView(
            String id, String agentId, String title, Integer messageCount, String source) {}

    /** Message view returned to clients for history replay. */
    public record MessageView(String id, String role, String contentJson, String createdAt) {}

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public SessionController(
            ChatSessionRepository sessionRepository, ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @GetMapping("/api/agents/{agentId}/sessions")
    public Mono<ResponseEntity<List<SessionView>>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String agentId) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        UUID agentUuid = parseUuid(agentId);
        if (agentUuid == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(
                        () ->
                                sessionRepository
                                        .findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
                                                orgId, userId, agentUuid)
                                        .stream()
                                        .map(SessionController::toView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * Returns the ordered message history of a session. The session must belong to the caller
     * (org + user + agent), otherwise 404 — users cannot read another user's history.
     */
    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<List<MessageView>>> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionId) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionId);
        if (agentUuid == null || sessionUuid == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    sessionRepository
                                            .findByIdAndOrgIdAndUserIdAndAgentId(
                                                    sessionUuid, orgId, userId, agentUuid)
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

    /**
     * Deletes a session and its messages. The session must belong to the caller (org + user +
     * agent), otherwise 404 — users cannot delete another user's sessions.
     */
    @DeleteMapping("/api/agents/{agentId}/sessions/{sessionId}")
    public Mono<ResponseEntity<Void>> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionId) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionId);
        if (agentUuid == null || sessionUuid == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    sessionRepository
                                            .findByIdAndOrgIdAndUserIdAndAgentId(
                                                    sessionUuid, orgId, userId, agentUuid)
                                            .orElse(null);
                            if (session == null) {
                                return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
                            }
                            messageRepository.deleteBySessionId(session.getId());
                            sessionRepository.delete(session);
                            return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Deprecated flat route listing all of the caller's sessions across agents. Forwards from the
     * pre-F2 shape {@code GET /api/sessions}. Remove once the console frontend migrates to the
     * agent-scoped route (Phase F6).
     */
    @Deprecated(since = "F2", forRemoval = true)
    @GetMapping("/api/sessions")
    public Mono<ResponseEntity<List<SessionView>>> listLegacy(@AuthenticationPrincipal Jwt jwt) {
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
     * Deprecated flat route for session history. Forwards from the pre-F2 shape {@code
     * GET /api/sessions/{sessionId}/messages} (org+user guarded, cross-agent). Remove at Phase F6.
     */
    @Deprecated(since = "F2", forRemoval = true)
    @GetMapping("/api/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<List<MessageView>>> messagesLegacy(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        UUID sessionUuid = parseUuid(sessionId);
        if (sessionUuid == null) {
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

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
                e.getContentJson(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
