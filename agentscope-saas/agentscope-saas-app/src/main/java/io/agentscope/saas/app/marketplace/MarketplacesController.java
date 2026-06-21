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
package io.agentscope.saas.app.marketplace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.persistence.entity.MarketplaceEntity;
import io.agentscope.saas.core.persistence.repo.MarketplaceRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org-scoped skill marketplace management (Phase F5). CRUD over the org's marketplaces (stored as
 * {@link MarketplaceEntity} rows, isolated by RLS), plus read-only browsing of the skills each
 * marketplace exposes via the live {@link MarketplaceRegistry}, and a connection probe.
 *
 * <p>Endpoints are scoped to the caller's org ({@code org_id} from the JWT); the path does not
 * include an {@code agentId} segment since marketplaces are org-wide, not per-agent. Per-agent
 * install lives on {@code AgentSkillsController}, which targets the agent's workspace {@code
 * skills/}. Ported from the desktop {@code MarketplacesController}, replacing the on-disk {@code
 * agentscope.json} file with the per-tenant DB table.
 *
 * <p><b>Authorization</b>: write endpoints (create / update / delete / connection-test) are gated
 * to the {@code admin} JWT role — a marketplace config carries git/nacos connection credentials, so
 * changing the org's marketplaces is an admin action (mirrors {@code OrgToolsConfigController} for
 * MCP). Read endpoints (list marketplaces, browse skills) and per-agent install remain available
 * to every org member: a member still browses marketplaces and installs skills into their own
 * workspace (install reads the {@link MarketplaceRegistry} bean directly, not these endpoints).
 */
@RestController
@RequestMapping("/api/marketplaces")
public class MarketplacesController {

    private static final Logger log = LoggerFactory.getLogger(MarketplacesController.class);
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /** Property keys never echoed back in API responses; passwords would leak otherwise. */
    private static final Set<String> SECRET_KEYS = Set.of("password", "secretKey");

    private static final Set<String> SUPPORTED_TYPES = Set.of("git", "nacos");

    /** JWT role value permitted to manage org marketplaces (write endpoints). */
    private static final String ADMIN_ROLE = "admin";

    private final MarketplaceRepository repository;
    private final MarketplaceRegistry registry;
    private final ObjectMapper objectMapper;

    public MarketplacesController(
            MarketplaceRepository repository,
            MarketplaceRegistry registry,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    //  CRUD
    // -----------------------------------------------------------------

    @GetMapping("")
    public Mono<List<MarketplaceSummary>> listMarketplaces(@AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () ->
                                repository.findByOrgIdOrderByIdAsc(orgId).stream()
                                        .map(
                                                e ->
                                                        toSummary(
                                                                e.getMarketplaceId(),
                                                                registry.toConfigEntry(e)))
                                        .sorted(Comparator.comparing(MarketplaceSummary::id))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("")
    @Transactional
    public Mono<MarketplaceSummary> createMarketplace(
            @RequestBody MarketplaceWriteRequest req, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateRequest(req, true);
                            String id = req.id().trim();
                            if (repository.findByOrgIdAndMarketplaceId(orgId, id).isPresent()) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Marketplace already exists: " + id);
                            }
                            MarketplaceEntity entity = newEntity(orgId, id, req);
                            entity = repository.save(entity);
                            return toSummary(id, registry.toConfigEntry(entity));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    @Transactional
    public Mono<MarketplaceSummary> updateMarketplace(
            @PathVariable String id,
            @RequestBody MarketplaceWriteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateId(id);
                            if (req == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "request body is required");
                            }
                            // Body's id is ignored on PUT; the path id wins so a typo can't move
                            // the entry.
                            MarketplaceWriteRequest normalized =
                                    new MarketplaceWriteRequest(id, req.type(), req.properties());
                            validateRequest(normalized, false);
                            MarketplaceEntity entity =
                                    repository
                                            .findByOrgIdAndMarketplaceId(orgId, id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Marketplace not found: "
                                                                            + id));
                            applyConfig(entity, normalized);
                            entity.setUpdatedAt(OffsetDateTime.now());
                            entity = repository.save(entity);
                            registry.evict(orgId, id);
                            return toSummary(id, registry.toConfigEntry(entity));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public Mono<Void> deleteMarketplace(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromRunnable(
                        () -> {
                            validateId(id);
                            long removed = repository.deleteByOrgIdAndMarketplaceId(orgId, id);
                            if (removed == 0) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                            }
                            registry.evict(orgId, id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // -----------------------------------------------------------------
    //  Test connection
    // -----------------------------------------------------------------

    @PostMapping("/test")
    public Mono<TestConnectionResult> testTransient(
            @RequestBody MarketplaceWriteRequest req, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateRequest(req, true);
                            MarketplaceEntity entity = newEntity(orgId(jwt), req.id().trim(), req);
                            return probe(req.id().trim(), entity);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/test")
    public Mono<TestConnectionResult> testExisting(
            @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            validateId(id);
                            MarketplaceEntity entity =
                                    repository
                                            .findByOrgIdAndMarketplaceId(orgId, id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "Marketplace not found: "
                                                                            + id));
                            return probe(id, entity);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // -----------------------------------------------------------------
    //  Skill listing / fetching
    // -----------------------------------------------------------------

    @GetMapping("/{id}/skills")
    public Mono<List<MarketSkillBrief>> listSkills(
            @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            Marketplace mp = requireRegistered(orgId, id);
                            List<MarketSkillSummary> raw;
                            try {
                                raw = mp.list();
                            } catch (RuntimeException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Marketplace listing failed: " + e.getMessage(),
                                        e);
                            }
                            List<MarketSkillBrief> out = new ArrayList<>(raw.size());
                            for (MarketSkillSummary s : raw) {
                                out.add(
                                        new MarketSkillBrief(
                                                s.name(), s.description(), s.version()));
                            }
                            out.sort(Comparator.comparing(MarketSkillBrief::name));
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/skills/{name}")
    public Mono<MarketSkillDetail> getSkill(
            @PathVariable String id, @PathVariable String name, @AuthenticationPrincipal Jwt jwt) {
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            if (name == null || name.isBlank()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "skill name is required");
                            }
                            Marketplace mp = requireRegistered(orgId, id);
                            MarketSkillContent content;
                            try {
                                content = mp.fetch(name);
                            } catch (RuntimeException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Marketplace fetch failed: " + e.getMessage(),
                                        e);
                            }
                            if (content == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Skill '"
                                                + name
                                                + "' not found in marketplace '"
                                                + id
                                                + "'");
                            }
                            Map<String, String> resources =
                                    content.resources() != null
                                            ? new LinkedHashMap<>(content.resources())
                                            : Map.of();
                            return new MarketSkillDetail(
                                    content.name(),
                                    content.description(),
                                    content.markdown(),
                                    resources);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private Marketplace requireRegistered(UUID orgId, String id) {
        validateId(id);
        return registry.find(orgId, id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Marketplace not registered: " + id));
    }

    private TestConnectionResult probe(String id, MarketplaceEntity entity) {
        Marketplace mp;
        try {
            mp = registry.build(entity);
        } catch (IllegalArgumentException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } catch (RuntimeException e) {
            return new TestConnectionResult(
                    false, "Failed to build marketplace: " + e.getMessage(), null);
        }
        try {
            // For probes we don't need the full list; for git this forces a clone, proving
            // credentials and the skills/ layout in one call.
            List<MarketSkillSummary> sample = mp.list();
            int total = sample != null ? sample.size() : 0;
            return new TestConnectionResult(true, "Connected", total);
        } catch (RuntimeException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } finally {
            try {
                mp.close();
            } catch (RuntimeException ignored) {
                // probe is throwaway — failure to release transient resources is non-fatal
            }
        }
    }

    private static void validateRequest(MarketplaceWriteRequest req, boolean requireId) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (requireId) {
            validateId(req.id());
        }
        if (req.type() == null || req.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        String type = req.type().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported marketplace type '"
                            + req.type()
                            + "', expected one of "
                            + SUPPORTED_TYPES);
        }
        Map<String, Object> props = req.properties();
        if ("git".equals(type)) {
            requireStringProp(props, "remoteUrl");
        } else if ("nacos".equals(type)) {
            requireStringProp(props, "serverAddr");
        }
    }

    private static void requireStringProp(Map<String, Object> props, String key) {
        if (props == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
        Object v = props.get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid marketplace id: '" + id + "' (allowed: letters, digits, ._-)");
        }
    }

    private MarketplaceEntity newEntity(UUID orgId, String id, MarketplaceWriteRequest req) {
        MarketplaceEntity entity = new MarketplaceEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setMarketplaceId(id);
        applyConfig(entity, req);
        return entity;
    }

    private void applyConfig(MarketplaceEntity entity, MarketplaceWriteRequest req) {
        entity.setType(req.type().toLowerCase(Locale.ROOT));
        entity.setProperties(serializeProperties(req));
    }

    private String serializeProperties(MarketplaceWriteRequest req) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(req.type().toLowerCase(Locale.ROOT));
        if (req.properties() != null) {
            for (Map.Entry<String, Object> e : req.properties().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize marketplace config: " + e.getMessage());
        }
    }

    private static MarketplaceSummary toSummary(String id, MarketplaceConfigEntry entry) {
        // Strip secrets from the surfaced property bag so they never round-trip through the UI.
        Map<String, Object> safeProps = new LinkedHashMap<>();
        if (entry.getProperties() != null) {
            for (Map.Entry<String, Object> e : entry.getProperties().entrySet()) {
                if (SECRET_KEYS.contains(e.getKey())) continue;
                safeProps.put(e.getKey(), e.getValue());
            }
        }
        return new MarketplaceSummary(id, entry.getType(), safeProps);
    }

    private static UUID orgId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("org_id"));
    }

    /**
     * Rejects non-admin callers from write endpoints with 403 (401 when unauthenticated). Marketplace
     * config carries connection credentials, so managing the org's marketplaces is an admin action —
     * mirrors {@code OrgToolsConfigController.requireAdmin}.
     */
    private static void requireAdmin(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        String role = jwt.getClaimAsString("role");
        if (!ADMIN_ROLE.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    /**
     * Write payload for create / update / test. {@code id} is required on create, ignored on update
     * (path wins), and used as a label on transient test. {@code properties} carries type-specific
     * fields (git: remoteUrl, branch; nacos: serverAddr, namespaceId, username, password, accessKey,
     * secretKey).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceWriteRequest(String id, String type, Map<String, Object> properties) {}

    /** Response payload — never carries credentials. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceSummary(String id, String type, Map<String, Object> properties) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestConnectionResult(boolean ok, String message, Integer skillCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillBrief(String name, String description, String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}
}
