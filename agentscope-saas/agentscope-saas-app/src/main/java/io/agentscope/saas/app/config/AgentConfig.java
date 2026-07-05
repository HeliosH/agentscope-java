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
package io.agentscope.saas.app.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.memory.mem0.Mem0Client;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.tools.McpClientRegistry;
import io.agentscope.saas.app.memory.MemoryLedger;
import io.agentscope.saas.app.memory.SaasLongTermMemoryMiddleware;
import io.agentscope.saas.app.org.OrgToolsConfigService;
import io.agentscope.saas.app.workspace.WorkspaceProjectionCatalogSink;
import io.agentscope.saas.core.middleware.RateLimitMiddleware;
import io.agentscope.saas.core.middleware.TenantContextMiddleware;
import io.agentscope.saas.core.middleware.UsageMeteringMiddleware;
import io.agentscope.saas.core.ratelimit.RateLimiter;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.usage.UsageService;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.agentscope.saas.sandbox.middleware.SandboxQuotaMiddleware;
import io.agentscope.saas.sandbox.middleware.SandboxTrackingMiddleware;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the singleton {@link HarnessAgent} that serves all tenants. Per-call isolation is
 * achieved through the {@code RuntimeContext} (userId/sessionId) supplied by the chat controller,
 * which the framework uses to namespace state and (when enabled) sandboxes.
 *
 * <p>The SaaS middleware chain (tenant → rate-limit → sandbox-quota → usage) is prepended; the
 * framework appends its built-in sandbox-lifecycle, permission, and trace middlewares.
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public HarnessAgent harnessAgent(
            Model chatModel,
            AgentStateStore agentStateStore,
            RateLimiter rateLimiter,
            UsageService usageService,
            SaasProperties properties,
            ObjectProvider<SandboxFilesystemSpec> sandboxSpecProvider,
            ObjectProvider<SandboxBroker> sandboxBrokerProvider,
            ObjectProvider<SandboxMetrics> sandboxMetricsProvider,
            ObjectProvider<BaseStore> workspaceStoreProvider,
            McpClientRegistry mcpClientRegistry,
            OrgToolsConfigService orgToolsConfigService,
            ObjectProvider<MemoryLedger> memoryLedgerProvider,
            ObjectProvider<WorkspaceProjectionCatalogSink> workspaceProjectionCatalogSinkProvider,
            ObjectProvider<MemoryConsolidator.ConsolidationSink> consolidationSinkProvider) {

        SaasProperties.Agent agentCfg = properties.getAgent();
        SaasProperties.RateLimit rl = properties.getRateLimit();

        log.info(
                "Assembling HarnessAgent name={} maxIters={} model={} sandbox={}",
                agentCfg.getName(),
                agentCfg.getMaxIters(),
                chatModel.getModelName(),
                properties.getSandbox().isEnabled());

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(agentCfg.getName())
                        .sysPrompt(agentCfg.getSysPrompt())
                        .model(chatModel)
                        .maxIters(agentCfg.getMaxIters())
                        .stateStore(agentStateStore)
                        .defaultSessionId("default")
                        .middleware(new TenantContextMiddleware())
                        .middleware(
                                new RateLimitMiddleware(
                                        rateLimiter, rl.getMaxRequests(), rl.getWindowSeconds()))
                        .middleware(new UsageMeteringMiddleware(usageService));

        MemoryConsolidator.ConsolidationSink consolidationSink =
                consolidationSinkProvider.getIfAvailable();
        if (consolidationSink != null) {
            builder.memory(MemoryConfig.builder().consolidationSink(consolidationSink).build());
        }

        // When sandbox is enabled, wire the SandboxFilesystemSpec which drives the framework's
        // internal sandbox lifecycle (SandboxManager, SessionSandboxStateStore,
        // SandboxLifecycleMiddleware, SandboxBackedFilesystem). Also add the SandboxQuotaMiddleware
        // to enforce per-tenant sandbox limits. When disabled, remove the shell tool so the agent
        // does not attempt to exec in an unconfigured environment.
        SandboxFilesystemSpec sandboxSpec = sandboxSpecProvider.getIfAvailable();
        if (properties.getSandbox().isEnabled() && sandboxSpec != null) {
            SandboxMetrics sandboxMetrics =
                    sandboxMetricsProvider.getIfAvailable(SandboxMetrics::noop);
            SandboxBroker broker = sandboxBrokerProvider.getIfAvailable();
            builder.sandboxLifecycleObserver(
                    new MeteredSandboxLifecycleObserver(
                            properties.getSandbox().getType(), sandboxMetrics, broker));
            // F3-S2: when a BaseStore (Redis/Oss) is available, wire a remote projection so
            // workspace files (MEMORY.md, skills, …) stay readable/writable between calls. Without
            // this, SandboxBackedFilesystem throws "No active sandbox" on out-of-call IO (the
            // frontend workspace/skill pages 500 between chats). Projection reuses the same
            // per-tenant namespace as the sandbox-off RemoteFilesystemSpec path.
            BaseStore projectionStore = workspaceStoreProvider.getIfAvailable();
            if (projectionStore != null) {
                sandboxSpec.remoteProjection(projectionStore, AgentConfig::tenantNamespace);
                sandboxSpec.workspaceProjectionSink(
                        workspaceProjectionCatalogSinkProvider.getIfAvailable());
                log.info(
                        "Sandbox remote projection enabled (out-of-call file IO delegates to"
                                + " BaseStore: {})",
                        projectionStore.getClass().getSimpleName());
            } else {
                log.info(
                        "Sandbox remote projection disabled (no BaseStore bean); out-of-call"
                                + " workspace IO will throw 'No active sandbox'");
            }
            builder.filesystem(sandboxSpec);
            if (broker != null) {
                builder.middleware(new SandboxQuotaMiddleware(broker));
                builder.middleware(
                        new SandboxTrackingMiddleware(
                                broker,
                                properties.getSandbox().getType(),
                                properties.getSandbox().getIdleTtlSeconds(),
                                sandboxMetrics));
            }
            // Skill self-evolution (Phase B): with a workspace filesystem present, let the agent
            // propose/promote skills from its own runs and run the background curator that
            // consolidates them. The framework's HarnessSkillMiddleware + WorkspaceSkillRepository
            // (wired by default) resolve skills per-request via RuntimeContext, so each user's
            // sandbox workspace holds its own isolated skill tree.
            if (agentCfg.getSkills().isSelfEvolution()) {
                builder.enableSkillManageTool(true);
                builder.enableSkillCurator(SkillCuratorConfig.defaults());
            }
        } else {
            // Sandbox off: optionally give each user an isolated workspace backed by the Redis
            // store (no Docker/FUSE). RemoteFilesystemSpec routes MEMORY.md/skills/memory/... into
            // per-user namespaces via IsolationScope.USER. It has no shell, so the shell tool stays
            // disabled below. The effective AgentStateStore is RedisAgentStateStore (distributed)
            // whenever the workspace store bean exists, satisfying RemoteFilesystemSpec's
            // distributed-store requirement; when Redis is off, no store is present and the agent
            // falls back to a shell-less, filesystem-less config (the local-profile behavior).
            BaseStore remoteStore = workspaceStoreProvider.getIfAvailable();
            if (remoteStore != null) {
                builder.filesystem(
                        new RemoteFilesystemSpec(remoteStore)
                                .isolationScope(IsolationScope.USER)
                                .namespaceFactory(AgentConfig::tenantNamespace));
                if (agentCfg.getSkills().isSelfEvolution()) {
                    builder.enableSkillManageTool(true);
                    builder.enableSkillCurator(SkillCuratorConfig.defaults());
                }
            }
            builder.disableShellTool();
        }

        // Permission engine (tool_guard, Phase B4′): tool-name-level ALLOW/ASK/DENY rules so
        // read-only tools are auto-allowed, mutating/shell tools require confirmation (HITL via
        // REQUIRE_USER_CONFIRM), and explicitly denied tools are blocked. Mode DEFAULT pauses on
        // ASK; DONT_ASK demotes ASK to DENY for unattended runs. Unset tool names are no-ops.
        PermissionContextState permissionContext = buildPermissionContext(agentCfg);
        builder.permissionContext(permissionContext);
        log.info(
                "Permission tool_guard mode={} allow={} ask={} deny={}",
                agentCfg.getPermission().getMode(),
                permissionContext.getAllowRules().size(),
                permissionContext.getAskRules().size(),
                permissionContext.getDenyRules().size());

        // Long-term memory (Phase F7b): when enabled, retrieve + record Mem0 memories per tenant
        // around each call. Disabled by default — the agent falls back to MEMORY.md + snapshot.
        SaasProperties.Ltm ltmCfg = properties.getLtm();
        if (ltmCfg.isEnabled()
                && ltmCfg.getMem0BaseUrl() != null
                && !ltmCfg.getMem0BaseUrl().isBlank()) {
            Mem0Client mem0Client =
                    SaasLongTermMemoryMiddleware.createClient(
                            ltmCfg.getMem0BaseUrl(),
                            ltmCfg.getMem0ApiKey(),
                            ltmCfg.getMem0ApiType(),
                            ltmCfg.getTimeoutSeconds());
            builder.middleware(
                    new SaasLongTermMemoryMiddleware(
                            mem0Client,
                            agentCfg.getName(),
                            ltmCfg.getTopK(),
                            memoryLedgerProvider.getIfAvailable(MemoryLedger::noop)));
            log.info(
                    "LTM middleware enabled: mem0BaseUrl={} topK={} agentName={}",
                    ltmCfg.getMem0BaseUrl(),
                    ltmCfg.getTopK(),
                    agentCfg.getName());
        } else {
            log.info("LTM middleware disabled (saas.ltm.enabled=false or no mem0-base-url)");
        }

        // Dynamic per-user MCP resolution (Phase C2): the singleton agent re-resolves each caller's
        // tools.json (org base overlaid with the user's workspace file) every reasoning step and
        // keeps the live toolkit in sync, so each tenant sees their own MCP tools without a
        // restart.
        // The org-id extractor reads the tenant context the request middleware wrote into the
        // RuntimeContext; the org-base loader reads orgs.settings.mcpServers. No-op in dev/empty
        // contexts (extractor returns null → middleware leaves the toolkit as-is).
        builder.dynamicMcp(
                mcpClientRegistry,
                rc -> {
                    TenantContext tc = TenantContext.from(rc);
                    if (tc == null || tc.orgId() == null || tc.orgId().isBlank()) {
                        return null;
                    }
                    try {
                        return UUID.fromString(tc.orgId());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                },
                orgToolsConfigService::loadOrgBase);

        return builder.build();
    }

    /**
     * Shared live-MCP-client cache for
     * {@code io.agentscope.harness.agent.middleware.DynamicMcpMiddleware}. One bean for the singleton
     * agent; clients are keyed by (userId, serverName) inside the registry so concurrent per-user
     * turns are isolated.
     */
    @Bean
    public McpClientRegistry mcpClientRegistry() {
        return new McpClientRegistry();
    }

    /**
     * Builds the per-agent permission context from the tool_guard config (ALLOW/ASK/DENY rules).
     *
     * <p>Package-private for direct unit testing of the rule-construction logic (including
     * parameter-level {@code askRules}).
     */
    static PermissionContextState buildPermissionContext(SaasProperties.Agent agentCfg) {
        SaasProperties.Permission p = agentCfg.getPermission();
        PermissionMode mode = PermissionMode.valueOf(p.getMode().toUpperCase());
        PermissionContextState.Builder b = PermissionContextState.builder().mode(mode);
        for (String tool : p.getAllowTools()) {
            b.addAllowRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "tool_guard"));
        }
        for (String tool : p.getAskTools()) {
            b.addAskRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "tool_guard"));
        }
        // Parameter-level ASK rules: tool name -> regex applied to the tool's `command` argument.
        // These layer on top of the tool-name-level rules so a tool can be ALLOW by default yet
        // still ASK when the command matches a dangerous pattern (e.g. execute with "rm -rf").
        for (Map.Entry<String, String> entry : p.getAskRules().entrySet()) {
            String tool = entry.getKey();
            b.addAskRule(
                    tool,
                    new PermissionRule(
                            tool, entry.getValue(), PermissionBehavior.ASK, "tool_guard"));
        }
        for (String tool : p.getDenyTools()) {
            b.addDenyRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.DENY, "tool_guard"));
        }
        return b.build();
    }

    /**
     * Store-namespace factory that adds the {@code org} dimension to the per-user workspace, so
     * storage keys are physically partitioned by tenant as well as user: {@code [org, orgId, user,
     * userId]}. Sourced from the {@link TenantContext} carried in the per-call {@link
     * RuntimeContext}.
     *
     * <p>This is the multi-tenant isolation guarantee for the no-sandbox (Redis/MinIO BaseStore)
     * workspace path: even if two users across orgs happened to share a user id, their workspaces
     * (MEMORY.md, skills/, memory/, …) remain isolated by the org segment. The per-route segment
     * (memory/skills/…) is appended by {@link RemoteFilesystemSpec} on top of this.
     *
     * <p>When no tenant context is present (e.g. unauthenticated/system calls), degrades to an
     * {@code _anonymous} namespace so the call still resolves rather than failing.
     */
    static List<String> tenantNamespace(RuntimeContext rc) {
        if (rc == null) {
            return List.of("org", "_anonymous", "user", "_anonymous");
        }
        TenantContext tc = TenantContext.from(rc);
        if (tc == null) {
            return List.of("org", "_anonymous", "user", "_anonymous");
        }
        String orgId = tc.orgId() != null && !tc.orgId().isBlank() ? tc.orgId() : "_anonymous";
        String userId = tc.userId() != null && !tc.userId().isBlank() ? tc.userId() : "_anonymous";
        return List.of("org", orgId, "user", userId);
    }
}
