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
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.RuntimeToolScope;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.tools.McpClientRegistry;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.tools.ToolsConfigMerger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Resolves the caller's effective tool config once per Agent call and installs an immutable
 * call-scoped {@link RuntimeToolScope}. The singleton Agent's toolkit is never mutated.
 *
 * <p>This is the MCP analogue of {@link DynamicSubagentsMiddleware}: it reads the user's {@code
 * tools.json} through the per-user {@link AbstractFilesystem} namespace (resolved via the {@link
 * RuntimeContext} passed to {@code onAgent}), merges it with the organization base, copies the
 * platform toolkit, and registers only this caller's MCP clients on that copy. Model schemas,
 * permission checks, and execution subsequently resolve the same snapshot from RuntimeContext.
 *
 * <p>The org/user id extractors and the org-base loader are injected so this harness middleware
 * stays decoupled from the SaaS persistence layer ({@code TenantContext}/{@code OrgRepository}
 * live in saas-core, which depends on harness, not the reverse). Per-server failures are logged
 * and omit that server without exposing another tenant's tools. The effective
 * {@code allow}/{@code deny} filter is applied to the private copy as well.
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
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    installScope(agent, ctx);
                    return next.apply(input);
                });
    }

    private void installScope(Agent agent, RuntimeContext ctx) {
        if (filesystem == null || registry == null) {
            return;
        }
        UUID userId = safe(userIdExtractor, ctx);
        UUID orgId = safe(orgIdExtractor, ctx);
        if (userId == null) {
            // No tenant context on this call (e.g. dev/bypass); use the platform toolkit.
            return;
        }

        RuntimeToolScope installed = RuntimeToolScope.current(ctx);
        if (installed != null) {
            return;
        }

        ToolsConfig userCfg = readUserToolsJson(ctx);
        ToolsConfig orgCfg = orgId == null ? null : safeLoadOrgBase(orgId);
        ToolsConfig effective = ToolsConfigMerger.merge(orgCfg, userCfg);
        Map<String, McpServerConfig> wanted = effective.getMcpServers();
        Toolkit scopedToolkit = agent.getToolkit().copy();
        Map<String, String> contributions = new LinkedHashMap<>();
        if (wanted != null) {
            wanted.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> {
                                McpClientWrapper wrapper =
                                        registry.getOrCreate(
                                                userId, entry.getKey(), entry.getValue());
                                if (wrapper != null
                                        && register(
                                                scopedToolkit,
                                                entry.getKey(),
                                                wrapper,
                                                entry.getValue())) {
                                    contributions.put(
                                            "mcp:" + entry.getKey(),
                                            registry.configurationFingerprint(entry.getValue()));
                                }
                            });
        }
        ToolFilter.apply(scopedToolkit, effective);
        RuntimeToolScope.install(
                ctx, scopedToolkit, registry.configurationFingerprint(effective), contributions);
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

    private boolean register(
            Toolkit toolkit, String name, McpClientWrapper wrapper, McpServerConfig cfg) {
        try {
            toolkit.registration().mcpClient(wrapper).enableTools(cfg.getEnableTools()).apply();
            log.debug(
                    "Dynamic MCP scope: registered '{}' (transport={})", name, cfg.getTransport());
            return true;
        } catch (Exception e) {
            log.warn(
                    "Dynamic MCP scope: failed to register '{}' ({}): {}",
                    name,
                    cfg.getTransport(),
                    e.getMessage());
            return false;
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
