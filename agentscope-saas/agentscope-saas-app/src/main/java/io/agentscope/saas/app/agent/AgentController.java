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
package io.agentscope.saas.app.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org-scoped agent management, shaped to match the paw frontend's {@code AgentDefinition} contract so
 * the forked console works without translation. Every query is filtered by the caller's {@code
 * org_id} (and, for writes, {@code user_id}) claim; RLS provides the second layer of defense.
 *
 * <p>Timestamps are emitted as epoch milliseconds (paw convention). The {@code tools} column holds a
 * JSON array of tool names; it is round-tripped through {@link ObjectMapper} to/from {@code
 * List<String>}.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final AgentRepository agentRepository;
    private final ChatPersistenceService persistenceService;
    private final AgentDraftService draftService;
    private final ObjectMapper objectMapper;
    private final TenantResolver tenantResolver;

    public AgentController(
            AgentRepository agentRepository,
            ChatPersistenceService persistenceService,
            AgentDraftService draftService,
            ObjectMapper objectMapper,
            TenantResolver tenantResolver) {
        this.agentRepository = agentRepository;
        this.persistenceService = persistenceService;
        this.draftService = draftService;
        this.objectMapper = objectMapper;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Agent view matching paw's {@code AgentDefinition}. {@code createdAt}/{@code updatedAt} are epoch
     * millis; built-in agents (none in SaaS today) would report 0. {@code tools} is null when unset.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentView(
            String id,
            String name,
            String description,
            String sysPrompt,
            Integer maxIters,
            List<String> tools,
            boolean builtin,
            long createdAt,
            long updatedAt,
            String workspacePath) {}

    /** Create/update payload — paw's {@code AgentCreateRequest} subset (null fields = keep on PUT). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentWriteRequest(
            @JsonAlias("id") String id,
            String name,
            String description,
            String sysPrompt,
            Integer maxIters,
            List<String> tools,
            String workspacePath) {}

    /** AI-draft request body. */
    public record DraftRequest(String description) {}

    @GetMapping
    public Mono<ResponseEntity<List<AgentView>>> list(@AuthenticationPrincipal Jwt jwt) {
        TenantContext tenant = tenant(jwt);
        if (!isUuid(tenant.orgId()) || !isUuid(tenant.userId())) {
            return Mono.just(ResponseEntity.ok(List.of()));
        }
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID userId = UUID.fromString(tenant.userId());
        return Mono.fromCallable(
                        () ->
                                agentRepository
                                        .findByOrgIdAndUserIdOrderByUpdatedAtDesc(orgId, userId)
                                        .stream()
                                        .map(AgentController.this::toView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<AgentView> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(tenant(jwt));
        return Mono.fromCallable(
                        () ->
                                agentRepository
                                        .findByIdAndOrgId(parseUuid(id), orgId)
                                        .map(AgentController.this::toView)
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "Agent not found: " + id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<AgentView>> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody AgentWriteRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
        }
        TenantContext tenant = tenant(jwt);
        UUID orgId = orgId(tenant);
        UUID userId = userId(tenant);
        return Mono.fromCallable(
                        () -> {
                            AgentEntity entity = new AgentEntity();
                            entity.setId(UUID.randomUUID());
                            entity.setOrgId(orgId);
                            entity.setUserId(userId);
                            entity.setName(request.name().trim());
                            entity.setVisibility("private");
                            entity.setStatus("active");
                            entity.setBuiltin(false);
                            applyWrite(entity, request);
                            entity.setUpdatedAt(OffsetDateTime.now());
                            return ResponseEntity.status(HttpStatus.CREATED)
                                    .body(toView(agentRepository.save(entity)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<AgentView> update(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AgentWriteRequest request) {
        TenantContext tenant = tenant(jwt);
        UUID orgId = orgId(tenant);
        UUID userId = userId(tenant);
        return Mono.fromCallable(
                        () -> {
                            AgentEntity entity =
                                    agentRepository
                                            .findByIdAndOrgId(parseUuid(id), orgId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Agent not found: " + id));
                            if (!userId.equals(entity.getUserId())) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "Not the agent owner");
                            }
                            // Per-field null-coalesce (paw semantics): null request field = keep
                            // existing.
                            if (request != null) {
                                if (request.name() != null && !request.name().isBlank()) {
                                    entity.setName(request.name().trim());
                                }
                                entity.setDescription(request.description());
                                entity.setSysPrompt(request.sysPrompt());
                                entity.setMaxIters(request.maxIters());
                                if (request.tools() != null) {
                                    entity.setTools(serializeTools(request.tools()));
                                }
                                // workspacePath is creation-only (paw); ignored on update.
                            }
                            entity.setUpdatedAt(OffsetDateTime.now());
                            return toView(agentRepository.save(entity));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        TenantContext tenant = tenant(jwt);
        UUID orgId = orgId(tenant);
        UUID userId = userId(tenant);
        return Mono.fromCallable(
                        () -> {
                            AgentEntity entity =
                                    agentRepository
                                            .findByIdAndOrgId(parseUuid(id), orgId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Agent not found: " + id));
                            if (!userId.equals(entity.getUserId())) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "Not the agent owner");
                            }
                            // Cascade the agent's sessions + messages in one transaction (the
                            // derived deleteBySessionId queries require a tx on boundedElastic).
                            // The
                            // per-user workspace is shared across agents (keyed by userId), so it
                            // is
                            // intentionally NOT deleted.
                            persistenceService.deleteAgentCascade(entity);
                            return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/draft")
    public Mono<AgentDraftService.AgentDraft> draft(@RequestBody DraftRequest request) {
        return draftService.draft(request == null ? null : request.description());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private TenantContext tenant(Jwt jwt) {
        return tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of());
    }

    private static UUID orgId(TenantContext tenant) {
        return uuidClaim("org_id", tenant.orgId());
    }

    private static UUID userId(TenantContext tenant) {
        return uuidClaim("user_id", tenant.userId());
    }

    private static UUID uuidClaim(String name, String value) {
        if (!isUuid(value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant " + name);
        }
        return UUID.fromString(value);
    }

    private static boolean isUuid(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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

    private void applyWrite(AgentEntity entity, AgentWriteRequest req) {
        entity.setDescription(req.description());
        entity.setSysPrompt(req.sysPrompt());
        entity.setMaxIters(req.maxIters());
        entity.setWorkspacePath(req.workspacePath());
        if (req.tools() != null) {
            entity.setTools(serializeTools(req.tools()));
        }
    }

    private AgentView toView(AgentEntity e) {
        return new AgentView(
                e.getId().toString(),
                e.getName(),
                e.getDescription(),
                e.getSysPrompt(),
                e.getMaxIters(),
                deserializeTools(e.getTools()),
                e.isBuiltin(),
                toEpochMillis(e.getCreatedAt()),
                toEpochMillis(e.getUpdatedAt()),
                e.getWorkspacePath());
    }

    private static long toEpochMillis(OffsetDateTime t) {
        return t == null ? 0L : t.toInstant().toEpochMilli();
    }

    private String serializeTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(tools);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize tools", e);
        }
    }

    private List<String> deserializeTools(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to deserialize agent tools JSON '{}': {}", json, e.getMessage());
            return null;
        }
    }

    /** Reserved for future use (e.g. error envelope parity); currently unused. */
    @SuppressWarnings("unused")
    private static Map<String, Object> errorBody(String message) {
        return Map.of("message", message);
    }
}
