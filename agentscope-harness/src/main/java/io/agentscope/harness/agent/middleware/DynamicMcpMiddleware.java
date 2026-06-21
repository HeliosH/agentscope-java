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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.tools.McpClientRegistry;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.tools.ToolsConfigMerger;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Re-resolves the caller's effective MCP-server config every reasoning step and keeps the agent's
 * live {@link io.agentscope.core.tool.Toolkit} in sync, so that a multi-tenant SaaS deployment
 * (where one singleton agent serves many users) presents each user's own MCP tools — org base
 * overlaid with that user's workspace {@code tools.json} — without an app restart.
 *
 * <p>This is the MCP analogue of {@link DynamicSubagentsMiddleware}: it reads the user's {@code
 * tools.json} through the per-user {@link AbstractFilesystem} namespace (resolved via the {@link
 * RuntimeContext} the framework passes to {@code onReasoning}), merges it with an org-level base
 * (loaded through the injected {@code orgBaseLoader}), and reconciles the agent's toolkit:
 *
 * <ul>
 *   <li>new servers → {@link McpClientRegistry#getOrCreate} (cached, no reconnect on later turns)
 *       then {@code toolkit.registerMcpClient(wrapper).block()}
 *   <li>removed servers → {@code toolkit.removeMcpClient(name).block()} then {@link
 *       McpClientRegistry#remove}
 * </ul>
 *
 * <p>The org/user id extractors and the org-base loader are injected so this harness middleware
 * stays decoupled from the SaaS persistence layer ({@code TenantContext}/{@code OrgRepository}
 * live in saas-core, which depends on harness, not the reverse). Failures at any step are logged
 * and swallowed — one bad MCP entry or a transient filesystem error never aborts the reasoning
 * loop (same convention as {@link io.agentscope.harness.agent.tools.McpServerRegistrar#register}).
 *
 * <p>The {@code allow}/{@code deny} builtin filter is <em>not</em> reconciled here per turn (the
 * harness applies it at build time via {@code ToolFilter}); this middleware only owns the MCP
 * server set. Builtin filter hot-reload is out of scope for C2.
 */
public class DynamicMcpMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpMiddleware.class);

    private static final String TOOLS_JSON = "tools.json";

    private final AbstractFilesystem filesystem;
    private final McpClientRegistry registry;
    private final Function<RuntimeContext, UUID> userIdExtractor;
    private final Function<RuntimeContext, UUID> orgIdExtractor;
    private final Function<UUID, ToolsConfig> orgBaseLoader;

    /**
     * @param filesystem the per-user workspace filesystem the agent was built with
     * @param registry the shared live-MCP-client cache
     * @param userIdExtractor maps the per-call {@link RuntimeContext} to the caller's user id
     * @param orgIdExtractor maps the per-call {@link RuntimeContext} to the caller's org id
     * @param orgBaseLoader loads the org-level base {@link ToolsConfig} for an org id (may return
     *     {@code null}; harness stays decoupled from the SaaS org store via this callback)
     */
    public DynamicMcpMiddleware(
            AbstractFilesystem filesystem,
            McpClientRegistry registry,
            Function<RuntimeContext, UUID> userIdExtractor,
            Function<RuntimeContext, UUID> orgIdExtractor,
            Function<UUID, ToolsConfig> orgBaseLoader) {
        this.filesystem = filesystem;
        this.registry = registry;
        this.userIdExtractor = userIdExtractor;
        this.orgIdExtractor = orgIdExtractor;
        this.orgBaseLoader = orgBaseLoader;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        try {
            reconcile(agent, ctx);
        } catch (Exception e) {
            log.warn("Dynamic MCP reconciliation failed: {}", e.getMessage());
        }
        return next.apply(input);
    }

    private void reconcile(Agent agent, RuntimeContext ctx) {
        if (filesystem == null || registry == null) {
            return;
        }
        UUID userId = safe(userIdExtractor, ctx);
        UUID orgId = safe(orgIdExtractor, ctx);
        if (userId == null) {
            // No tenant context on this turn (e.g. dev/bypass); leave the toolkit as-is.
            return;
        }

        ToolsConfig userCfg = readUserToolsJson(ctx);
        ToolsConfig orgCfg = orgId == null ? null : safeLoadOrgBase(orgId);
        ToolsConfig effective = ToolsConfigMerger.merge(orgCfg, userCfg);
        Map<String, McpServerConfig> wanted = effective.getMcpServers();
        Set<String> wantedNames = wanted == null ? Set.of() : new LinkedHashSet<>(wanted.keySet());

        Set<String> cached = registry.cachedServerNames(userId);
        // Remove servers that are no longer wanted.
        for (String name : new HashSet<>(cached)) {
            if (!wantedNames.contains(name)) {
                unregister(agent, name);
                registry.remove(userId, name);
            }
        }
        // Register servers not yet live. Re-registration of an already-cached server is a no-op
        // (the toolkit keeps it); we still touch the toolkit only when the cache missed so we
        // don't re-run the MCP handshake each turn.
        for (String name : wantedNames) {
            if (cached.contains(name)) {
                continue;
            }
            McpServerConfig cfg = wanted.get(name);
            McpClientWrapper wrapper = registry.getOrCreate(userId, name, cfg);
            if (wrapper == null) {
                continue;
            }
            register(agent, name, wrapper, cfg);
        }
    }

    private ToolsConfig readUserToolsJson(RuntimeContext ctx) {
        try {
            ReadResult rr = filesystem.read(ctx, "/" + TOOLS_JSON, 0, 0);
            if (rr == null || !rr.isSuccess() || rr.fileData() == null) {
                return null;
            }
            String raw = rr.fileData().content();
            return ToolsConfigLoader.parse(raw).orElse(null);
        } catch (Exception e) {
            // Missing tools.json is the common case (user hasn't configured any MCP); a transient
            // filesystem error is also non-fatal. Leave the user layer empty.
            log.debug("Could not read user tools.json ({}); skipping user layer.", e.getMessage());
            return null;
        }
    }

    private ToolsConfig safeLoadOrgBase(UUID orgId) {
        try {
            return orgBaseLoader.apply(orgId);
        } catch (Exception e) {
            log.warn("Failed to load org base tools config for {}: {}", orgId, e.getMessage());
            return null;
        }
    }

    private void register(Agent agent, String name, McpClientWrapper wrapper, McpServerConfig cfg) {
        try {
            agent.getToolkit().registerMcpClient(wrapper).block();
            log.info("Dynamic MCP: registered '{}' (transport={})", name, cfg.getTransport());
        } catch (Exception e) {
            log.warn(
                    "Dynamic MCP: failed to register '{}' ({}): {}",
                    name,
                    cfg.getTransport(),
                    e.getMessage());
        }
    }

    private void unregister(Agent agent, String name) {
        try {
            agent.getToolkit().removeMcpClient(name).block();
            log.info("Dynamic MCP: unregistered '{}'", name);
        } catch (Exception e) {
            log.warn("Dynamic MCP: failed to unregister '{}': {}", name, e.getMessage());
        }
    }

    private static UUID safe(Function<RuntimeContext, UUID> extractor, RuntimeContext ctx) {
        try {
            return extractor.apply(ctx);
        } catch (Exception e) {
            return null;
        }
    }
}
