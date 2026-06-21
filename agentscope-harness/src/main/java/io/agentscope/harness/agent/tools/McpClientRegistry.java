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

import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of live {@link McpClientWrapper} instances so that a dynamic per-user MCP
 * middleware can re-resolve a tenant's {@code tools.json} every reasoning step without reconnecting
 * MCP servers on every turn.
 *
 * <p>Clients are cached keyed by {@code (userId, serverName)}; {@link #getOrCreate} builds (via
 * {@link McpServerRegistrar#buildWrapper}) and caches a wrapper on first sight, {@link #remove}
 * closes and drops a single server, and {@link #closeAll} tears down every client for a user
 * (intended for session/logout cleanup). All map access is synchronized on {@code this} — the
 * registry is shared across the singleton agent's concurrent per-user turns.
 *
 * <p>Build failures are logged and return {@code null} rather than throwing, so one bad MCP entry
 * never aborts the agent's reasoning loop (same convention as {@link McpServerRegistrar#register}).
 */
public class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    /** userId -> (serverName -> live wrapper). */
    private final Map<UUID, Map<String, McpClientWrapper>> clients = new LinkedHashMap<>();

    /**
     * Returns the cached wrapper for {@code (userId, serverName)}, or builds and caches one from
     * {@code cfg} if absent. {@code cfg} is only read on the build path. Returns {@code null} if the
     * wrapper could not be built (bad transport / missing fields) — the caller should skip that
     * server.
     */
    public synchronized McpClientWrapper getOrCreate(
            UUID userId, String serverName, McpServerConfig cfg) {
        Map<String, McpClientWrapper> userMap =
                clients.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        McpClientWrapper existing = userMap.get(serverName);
        if (existing != null) {
            return existing;
        }
        try {
            McpClientWrapper wrapper = McpServerRegistrar.buildWrapper(serverName, cfg);
            if (wrapper == null) {
                return null;
            }
            userMap.put(serverName, wrapper);
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
        Map<String, McpClientWrapper> userMap = clients.get(userId);
        return userMap == null ? null : userMap.get(serverName);
    }

    /**
     * Closes and drops the wrapper for {@code (userId, serverName)}, if present. Returns {@code
     * true} if a client was removed.
     */
    public synchronized boolean remove(UUID userId, String serverName) {
        Map<String, McpClientWrapper> userMap = clients.get(userId);
        if (userMap == null) {
            return false;
        }
        McpClientWrapper wrapper = userMap.remove(serverName);
        if (wrapper == null) {
            return false;
        }
        closeQuietly(wrapper, serverName);
        return true;
    }

    /** Closes and drops every client owned by {@code userId}. */
    public synchronized void closeAll(UUID userId) {
        Map<String, McpClientWrapper> userMap = clients.remove(userId);
        if (userMap == null) {
            return;
        }
        for (Map.Entry<String, McpClientWrapper> e : userMap.entrySet()) {
            closeQuietly(e.getValue(), e.getKey());
        }
    }

    /** Returns the set of server names currently cached for {@code userId} (defensive copy). */
    public synchronized java.util.Set<String> cachedServerNames(UUID userId) {
        Map<String, McpClientWrapper> userMap = clients.get(userId);
        return userMap == null ? java.util.Set.of() : java.util.Set.copyOf(userMap.keySet());
    }

    private static void closeQuietly(McpClientWrapper wrapper, String name) {
        try {
            wrapper.close();
        } catch (Exception e) {
            log.warn("Error closing MCP client '{}': {}", name, e.getMessage());
        }
    }
}
