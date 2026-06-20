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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org/user-scoped chat session listing and history replay, nested under the agent resource
 * ({@code /api/agents/{agentId}/sessions}). Sessions are created lazily by the chat endpoint.
 *
 * <p>Two shapes are exposed:
 *
 * <ul>
 *   <li><b>paw inbox/turns</b> — {@code GET .../inbox}, {@code GET .../{sessionKey}}, {@code POST
 *       .../{sessionKey}/reset}, {@code PATCH .../{sessionKey}/read}, {@code DELETE .../{sessionKey}}.
 *       The session key is the session UUID string (zero mapping), matching paw's frontend contract.
 *       Turns are reconstructed from the persisted {@code content_json} blocks into paw's {@code
 *       TurnEntry} shape.
 *   <li><b>legacy message list</b> — {@code GET .../{sessionId}/messages} retained for back-compat.
 * </ul>
 *
 * <p>Every query is filtered by {@code (orgId, userId, agentId)} so a user only ever sees sessions
 * for agents they own within their tenant; RLS is the second layer of defense.
 */
@RestController
public class SessionController {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public SessionController(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            ChatPersistenceService persistenceService,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    //  Legacy session views (pre-paw shape) — retained for back-compat
    // -----------------------------------------------------------------

    public record SessionView(
            String id, String agentId, String title, Integer messageCount, String source) {}

    public record MessageView(String id, String role, String contentJson, String createdAt) {}

    @GetMapping("/api/agents/{agentId}/sessions")
    public Mono<ResponseEntity<List<SessionView>>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String agentId) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        return Mono.fromCallable(
                        () ->
                                sessionRepository
                                        .findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
                                                orgId, userId, agentUuid)
                                        .stream()
                                        .map(SessionController::toLegacyView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<List<MessageView>>> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionId) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionId);
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    requireSession(orgId, userId, agentUuid, sessionUuid);
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

    // -----------------------------------------------------------------
    //  paw inbox / turns / reset / read (sessionKey == sessionId.toString())
    // -----------------------------------------------------------------

    @GetMapping("/api/agents/{agentId}/sessions/inbox")
    public Mono<List<InboxEntry>> inbox(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        return Mono.fromCallable(
                        () -> {
                            int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
                            List<ChatSessionEntity> sessions =
                                    sessionRepository
                                            .findByOrgIdAndUserIdAndAgentIdOrderByUpdatedAtDesc(
                                                    orgId, userId, agentUuid);
                            List<InboxEntry> out = new ArrayList<>();
                            for (ChatSessionEntity s : sessions) {
                                if (unreadOnly && !s.isUnread()) continue;
                                out.add(
                                        new InboxEntry(
                                                s.getId().toString(),
                                                s.getId().toString(),
                                                s.getAgentId() == null
                                                        ? null
                                                        : s.getAgentId().toString(),
                                                s.getLabel() != null ? s.getLabel() : s.getTitle(),
                                                toEpochMillis(s.getUpdatedAt()),
                                                preview(s.getLastMessage()),
                                                s.isUnread()));
                                if (out.size() >= effectiveLimit) break;
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/agents/{agentId}/sessions/{sessionKey}")
    public Mono<List<TurnEntry>> turns(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionKey) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionKey);
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    requireSession(orgId, userId, agentUuid, sessionUuid);
                            List<TurnEntry> turns = new ArrayList<>();
                            String prevId = null;
                            for (ChatMessageEntity m :
                                    messageRepository.findBySessionIdOrderByCreatedAtAsc(
                                            session.getId())) {
                                TurnEntry t = toTurn(m, prevId);
                                turns.add(t);
                                prevId = t.id();
                            }
                            return turns;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Soft reset: clears the session's messages and resets bookkeeping (keeps the row). */
    @PostMapping("/api/agents/{agentId}/sessions/{sessionKey}/reset")
    public Mono<ResetResult> reset(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionKey) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionKey);
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    requireSession(orgId, userId, agentUuid, sessionUuid);
                            persistenceService.resetSession(session.getId());
                            return new ResetResult(sessionKey, true);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/api/agents/{agentId}/sessions/{sessionKey}/read")
    public Mono<ReadStateResult> markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionKey) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionKey);
        return Mono.fromCallable(
                        () -> {
                            ChatSessionEntity session =
                                    requireSession(orgId, userId, agentUuid, sessionUuid);
                            session.setUnread(false);
                            sessionRepository.save(session);
                            return new ReadStateResult(
                                    sessionKey, System.currentTimeMillis(), false);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** paw-style delete by sessionKey (UUID string). 204 on success, 404 when absent/foreign. */
    @DeleteMapping("/api/agents/{agentId}/sessions/{sessionKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteByKey(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String agentId,
            @PathVariable String sessionKey) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        UUID agentUuid = parseUuid(agentId);
        UUID sessionUuid = parseUuid(sessionKey);
        return Mono.fromRunnable(
                        () -> {
                            ChatSessionEntity session =
                                    requireSession(orgId, userId, agentUuid, sessionUuid);
                            persistenceService.deleteSession(session.getId());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // -----------------------------------------------------------------
    //  Legacy flat routes (deprecated, removed after F6 frontend migration)
    // -----------------------------------------------------------------

    @GetMapping("/api/sessions")
    public Mono<ResponseEntity<List<SessionView>>> listLegacy(@AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        UUID userId = userId(jwt);
        return Mono.fromCallable(
                        () ->
                                sessionRepository
                                        .findByOrgIdAndUserIdOrderByUpdatedAtDesc(orgId, userId)
                                        .stream()
                                        .map(SessionController::toLegacyView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private ChatSessionEntity requireSession(
            UUID orgId, UUID userId, UUID agentUuid, UUID sessionUuid) {
        return sessionRepository
                .findByIdAndOrgIdAndUserIdAndAgentId(sessionUuid, orgId, userId, agentUuid)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Session not found"));
    }

    private static UUID orgId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("org_id"));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id: " + s);
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id: " + s);
        }
    }

    private static long toEpochMillis(java.time.OffsetDateTime t) {
        return t == null ? 0L : t.toInstant().toEpochMilli();
    }

    private static String preview(String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) return null;
        String trimmed = lastMessage.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }

    private static SessionView toLegacyView(ChatSessionEntity e) {
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

    /**
     * Reconstructs a paw {@code TurnEntry} from a persisted message. The {@code content_json} array
     * of content blocks is flattened to a text {@code content} (concatenated text blocks); a tool-use
     * block (if present) populates {@code toolName}/{@code toolInput}. The role is upper-cased to
     * match paw's {@code USER}/{@code ASSISTANT}/{@code TOOL} convention.
     */
    private TurnEntry toTurn(ChatMessageEntity m, String parentId) {
        String text = null;
        String toolName = m.getToolName();
        String toolInput = m.getToolInput();
        if (m.getContentJson() != null && !m.getContentJson().isBlank()) {
            try {
                JsonNode blocks = objectMapper.readTree(m.getContentJson());
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : blocks) {
                    String type = block.path("type").asText("");
                    if ("text".equals(type)) {
                        String t = block.path("text").asText("");
                        if (!t.isEmpty()) sb.append(t);
                    } else if ("tool_use".equals(type) && toolName == null) {
                        toolName = block.path("name").asText(null);
                        JsonNode input = block.get("input");
                        if (input != null && toolInput == null) {
                            toolInput = input.toString();
                        }
                    }
                }
                if (sb.length() > 0) text = sb.toString();
            } catch (Exception ignored) {
                // leave text null
            }
        }
        String role = m.getRole() == null ? null : m.getRole().toUpperCase();
        return new TurnEntry(
                m.getId().toString(),
                parentId,
                role,
                text,
                toEpochMillis(m.getCreatedAt()),
                toolName,
                toolInput,
                m.getToolResult());
    }

    // -----------------------------------------------------------------
    //  DTOs (paw shapes)
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnEntry(
            String id,
            String parentId,
            String role,
            String content,
            long timestampMs,
            String toolName,
            String toolInput,
            String toolResult) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
