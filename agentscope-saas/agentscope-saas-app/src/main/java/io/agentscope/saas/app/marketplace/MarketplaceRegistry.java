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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.persistence.entity.MarketplaceEntity;
import io.agentscope.saas.core.persistence.repo.MarketplaceRepository;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds and caches live {@link Marketplace} instances from {@link MarketplaceEntity} rows, keyed by
 * (orgId, id) so each tenant's git clones / nacos clients are isolated. This is the only place that
 * knows how to map a {@link MarketplaceConfigEntry} to a concrete implementation, so adding a new
 * marketplace type means touching the {@link #build(MarketplaceEntity)} switch and nothing else.
 *
 * <p>Unlike the desktop {@code ClawMarketplaceRegistry} (which eagerly loads a bootstrap-time file
 * config), instances are built lazily on first access and evicted on a fixed TTL to bound stale
 * git clones; entity edits in the DB invalidate the cache entry via {@link #evict(UUID, String)}.
 */
@Component
public class MarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceRegistry.class);

    private final MarketplaceRepository repository;
    private final ObjectMapper objectMapper;
    private final Path gitCloneRoot;
    private final ConcurrentHashMap<CacheKey, Marketplace> instances = new ConcurrentHashMap<>();

    public MarketplaceRegistry(
            MarketplaceRepository repository,
            ObjectMapper objectMapper,
            MarketplaceProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.gitCloneRoot = properties.gitCloneRoot();
    }

    /** Find the live marketplace for (org, id), building + caching it on first access. */
    public Optional<Marketplace> find(UUID orgId, String id) {
        if (orgId == null || id == null) return Optional.empty();
        CacheKey key = new CacheKey(orgId, id);
        Marketplace cached = instances.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        MarketplaceEntity entity = repository.findByOrgIdAndMarketplaceId(orgId, id).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        try {
            Marketplace built = build(entity);
            Marketplace raced = instances.putIfAbsent(key, built);
            if (raced != null) {
                closeQuietly(built);
                return Optional.of(raced);
            }
            return Optional.of(built);
        } catch (RuntimeException e) {
            log.warn("Failed to build marketplace '{}': {}", id, e.getMessage(), e);
            throw e;
        }
    }

    /** Build a marketplace from an entity without caching it; used for connection probes. */
    public Marketplace build(MarketplaceEntity entity) {
        Objects.requireNonNull(entity, "entity");
        MarketplaceConfigEntry entry = toConfigEntry(entity);
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException(
                    "marketplace '" + entity.getMarketplaceId() + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        return switch (type) {
            case "git" -> buildGit(entity, props);
            case "nacos" -> buildNacos(entity, props);
            default ->
                    throw new IllegalArgumentException(
                            "unsupported marketplace type '"
                                    + entry.getType()
                                    + "' for '"
                                    + entity.getMarketplaceId()
                                    + "'");
        };
    }

    /** Drop the cached instance for (org, id) and close it; call after a DB edit. */
    public void evict(UUID orgId, String id) {
        Marketplace removed = instances.remove(new CacheKey(orgId, id));
        closeQuietly(removed);
    }

    /** Close every cached marketplace; used during shutdown so we don't leak git clones. */
    @PreDestroy
    public void closeAll() {
        instances.values().forEach(this::closeQuietly);
        instances.clear();
    }

    private Marketplace buildGit(MarketplaceEntity entity, Map<String, Object> props) {
        String id = entity.getMarketplaceId();
        String remoteUrl = stringProp(props, "remoteUrl");
        if (remoteUrl == null) {
            throw new IllegalArgumentException(
                    "git marketplace '" + id + "' requires a non-empty 'remoteUrl'");
        }
        String branch = stringProp(props, "branch");
        String skillsRoot = stringProp(props, "skillsRoot");
        Path localPath = gitCloneRoot.resolve(entity.getOrgId().toString()).resolve(id);
        return new GitMarketplace(id, remoteUrl, branch, localPath, skillsRoot);
    }

    private Marketplace buildNacos(MarketplaceEntity entity, Map<String, Object> props) {
        String id = entity.getMarketplaceId();
        String serverAddr = stringProp(props, "serverAddr");
        if (serverAddr == null) {
            throw new IllegalArgumentException(
                    "nacos marketplace '" + id + "' requires a non-empty 'serverAddr'");
        }
        return new NacosMarketplace(
                id,
                serverAddr,
                stringProp(props, "namespaceId"),
                stringProp(props, "username"),
                stringProp(props, "password"),
                stringProp(props, "accessKey"),
                stringProp(props, "secretKey"));
    }

    /** Deserialize the entity's {@code properties} JSON into a {@link MarketplaceConfigEntry}. */
    public MarketplaceConfigEntry toConfigEntry(MarketplaceEntity entity) {
        try {
            MarketplaceConfigEntry entry =
                    objectMapper.readValue(entity.getProperties(), MarketplaceConfigEntry.class);
            if (entry.getType() == null) {
                entry.setType(entity.getType());
            }
            return entry;
        } catch (Exception e) {
            MarketplaceConfigEntry fallback = new MarketplaceConfigEntry();
            fallback.setType(entity.getType());
            return fallback;
        }
    }

    private static String stringProp(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private void closeQuietly(Marketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }

    private record CacheKey(UUID orgId, String id) {}

    /** Minimal holder for the git clone root path; bound from {@code saas.marketplace.git-clone-root}. */
    public record MarketplaceProperties(Path gitCloneRoot) {}
}
