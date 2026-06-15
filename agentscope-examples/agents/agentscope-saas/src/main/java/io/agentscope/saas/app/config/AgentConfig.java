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

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.saas.core.middleware.RateLimitMiddleware;
import io.agentscope.saas.core.middleware.SandboxQuotaMiddleware;
import io.agentscope.saas.core.middleware.TenantContextMiddleware;
import io.agentscope.saas.core.middleware.UsageMeteringMiddleware;
import io.agentscope.saas.core.ratelimit.RateLimiter;
import io.agentscope.saas.core.sandbox.SandboxBroker;
import io.agentscope.saas.core.usage.UsageService;
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
            ObjectProvider<SandboxBroker> sandboxBrokerProvider) {

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
            }
        } else {
            builder.disableShellTool();
        }

        return builder.build();
    }
}
