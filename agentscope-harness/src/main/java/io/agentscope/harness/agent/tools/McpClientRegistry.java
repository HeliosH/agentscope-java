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
package io.agentscope.harness.agent.tools;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of live {@link McpClientWrapper} instances so that a dynamic per-user MCP
 * middleware can re-resolve a tenant's {@code tools.json} every Agent call without reconnecting MCP
 * servers on every reasoning step.
 *
 * <p>Clients are cached keyed by {@code (userId, serverName)}; {@link #getOrCreate} builds (via
 * {@link McpServerRegistrar#buildWrapper}) and caches a wrapper on first sight. Changed or removed
 * versions are retired without immediate close so in-flight immutable tool scopes remain valid;
 * {@link #closeAll} tears down current and retired clients for a user. All map access is synchronized
 * on {@code this}.
 *
 * <p>Build failures are logged and return {@code null} rather than throwing, so one bad MCP entry
 * never aborts the agent's reasoning loop (same convention as {@link McpServerRegistrar#register}).
 */
public class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    private static final ObjectMapper FINGERPRINT_MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** userId -> (serverName -> current versioned client). */
    private final Map<UUID, Map<String, ClientEntry>> clients = new LinkedHashMap<>();

    /** Replaced clients remain alive for in-flight call snapshots and close with the user scope. */
    private final Map<UUID, List<McpClientWrapper>> retiredClients = new LinkedHashMap<>();

    /**
     * Returns the cached wrapper for {@code (userId, serverName)}, or builds and caches one from
     * {@code cfg} if absent. {@code cfg} is only read on the build path. Returns {@code null} if the
     * wrapper could not be built (bad transport / missing fields) — the caller should skip that
     * server.
     */
    public synchronized McpClientWrapper getOrCreate(
            UUID userId, String serverName, McpServerConfig cfg) {
        Map<String, ClientEntry> userMap =
                clients.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        String fingerprint = configurationFingerprint(cfg);
        ClientEntry existing = userMap.get(serverName);
        if (existing != null && existing.fingerprint().equals(fingerprint)) {
            return existing.wrapper();
        }
        try {
            McpClientWrapper wrapper = McpServerRegistrar.buildWrapper(serverName, cfg);
            if (wrapper == null) {
                return null;
            }
            userMap.put(serverName, new ClientEntry(fingerprint, wrapper));
            if (existing != null) {
                retiredClients
                        .computeIfAbsent(userId, ignored -> new ArrayList<>())
                        .add(existing.wrapper());
            }
            return wrapper;
        } catch (Exception e) {
            log.warn(
                    "Failed to build MCP client '{}' for user={}: {}",
                    serverName,
                    userId,
                    e.getMessage());
            return null;
        }
    }

    /** Returns the cached wrapper for {@code (userId, serverName)} or {@code null} if absent. */
    public synchronized McpClientWrapper getIfPresent(UUID userId, String serverName) {
        Map<String, ClientEntry> userMap = clients.get(userId);
        ClientEntry entry = userMap == null ? null : userMap.get(serverName);
        return entry == null ? null : entry.wrapper();
    }

    /** Retires the current wrapper without invalidating in-flight call snapshots. */
    public synchronized boolean remove(UUID userId, String serverName) {
        Map<String, ClientEntry> userMap = clients.get(userId);
        if (userMap == null) {
            return false;
        }
        ClientEntry entry = userMap.remove(serverName);
        if (entry == null) {
            return false;
        }
        retiredClients.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(entry.wrapper());
        return true;
    }

    /** Closes and drops every client owned by {@code userId}. */
    public synchronized void closeAll(UUID userId) {
        Map<String, ClientEntry> userMap = clients.remove(userId);
        if (userMap != null) {
            for (Map.Entry<String, ClientEntry> e : userMap.entrySet()) {
                closeQuietly(e.getValue().wrapper(), e.getKey());
            }
        }
        List<McpClientWrapper> retired = retiredClients.remove(userId);
        if (retired != null) {
            retired.forEach(wrapper -> closeQuietly(wrapper, "retired"));
        }
    }

    /** Returns the set of server names currently cached for {@code userId} (defensive copy). */
    public synchronized java.util.Set<String> cachedServerNames(UUID userId) {
        Map<String, ClientEntry> userMap = clients.get(userId);
        return userMap == null ? java.util.Set.of() : java.util.Set.copyOf(userMap.keySet());
    }

    /** Returns a deterministic hash without exposing credentials from the configuration. */
    public String configurationFingerprint(Object configuration) {
        try {
            return io.agentscope.core.tool.RuntimeToolScope.hash(
                    FINGERPRINT_MAPPER.writeValueAsString(configuration));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to fingerprint MCP configuration", e);
        }
    }

    private static void closeQuietly(McpClientWrapper wrapper, String name) {
        try {
            wrapper.close();
        } catch (Exception e) {
            log.warn("Error closing MCP client '{}': {}", name, e.getMessage());
        }
    }

    private record ClientEntry(String fingerprint, McpClientWrapper wrapper) {}
}
