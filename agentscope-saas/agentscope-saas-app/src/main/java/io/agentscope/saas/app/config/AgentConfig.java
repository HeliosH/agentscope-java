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
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.saas.core.middleware.RateLimitMiddleware;
import io.agentscope.saas.core.middleware.TenantContextMiddleware;
import io.agentscope.saas.core.middleware.UsageMeteringMiddleware;
import io.agentscope.saas.core.ratelimit.RateLimiter;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.usage.UsageService;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.middleware.SandboxQuotaMiddleware;
import io.agentscope.saas.sandbox.middleware.SandboxTrackingMiddleware;
import java.util.List;
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
            ObjectProvider<BaseStore> workspaceStoreProvider) {

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

        // When sandbox is enabled, wire the SandboxFilesystemSpec which drives the framework's
        // internal sandbox lifecycle (SandboxManager, SessionSandboxStateStore,
        // SandboxLifecycleMiddleware, SandboxBackedFilesystem). Also add the SandboxQuotaMiddleware
        // to enforce per-tenant sandbox limits. When disabled, remove the shell tool so the agent
        // does not attempt to exec in an unconfigured environment.
        SandboxFilesystemSpec sandboxSpec = sandboxSpecProvider.getIfAvailable();
        if (properties.getSandbox().isEnabled() && sandboxSpec != null) {
            builder.filesystem(sandboxSpec);
            SandboxBroker broker = sandboxBrokerProvider.getIfAvailable();
            if (broker != null) {
                builder.middleware(new SandboxQuotaMiddleware(broker));
                builder.middleware(
                        new SandboxTrackingMiddleware(
                                broker,
                                properties.getSandbox().getType(),
                                properties.getSandbox().getIdleTtlSeconds()));
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

        return builder.build();
    }

    /** Builds the per-agent permission context from the tool_guard config (ALLOW/ASK/DENY rules). */
    private static PermissionContextState buildPermissionContext(SaasProperties.Agent agentCfg) {
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
        TenantContext tc = rc.get(TenantContext.class);
        if (tc == null) {
            return List.of("org", "_anonymous", "user", "_anonymous");
        }
        String orgId = tc.orgId() != null && !tc.orgId().isBlank() ? tc.orgId() : "_anonymous";
        String userId = tc.userId() != null && !tc.userId().isBlank() ? tc.userId() : "_anonymous";
        return List.of("org", orgId, "user", userId);
    }
}
