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

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ResilientModel;
import io.agentscope.saas.app.model.ModelCatalog;
import io.agentscope.saas.model.ScriptedToolModel;
import io.agentscope.saas.model.StubChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the {@link Model} bean according to {@code saas.model.type}:
 *
 * <ul>
 *   <li>{@code stub} — zero-dependency echo model (local smoke testing)</li>
 *   <li>{@code scripted} — zero-dependency model that drives the ReAct agent to execute the user's
 *       message as a shell command in the sandbox (dev profile functional verification)</li>
 *   <li>{@code gateway} — OpenAI-compatible internal model gateway (recommended for production; see
 *       {@code docs/enterprise-platform-java/07-model-gateway.md})</li>
 *   <li>{@code dashscope} — Alibaba DashScope (Qwen) direct</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SaasProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    @Bean
    public ModelCatalog chatModel(SaasProperties properties) {
        SaasProperties.Model cfg = properties.getModel();
        List<ModelCatalog.Route> catalogRoutes = new ArrayList<>();
        if (cfg.getCatalog().isEmpty()) {
            Model routeModel =
                    createGovernedRoute(
                            cfg.getType(),
                            cfg.getBaseUrl(),
                            cfg.getApiKey(),
                            cfg.getName(),
                            cfg.getFallbacks(),
                            cfg.getTraffic());
            catalogRoutes.add(
                    route(
                            cfg.getDefaultId(),
                            cfg.getDisplayName(),
                            cfg.getName(),
                            cfg.getContextWindowTokens(),
                            cfg.getMaxOutputTokens(),
                            cfg.getSafetyMarginTokens(),
                            true,
                            routeModel));
        } else {
            for (SaasProperties.ModelDefinition definition : cfg.getCatalog()) {
                if (!definition.isEnabled()) {
                    continue;
                }
                Model routeModel =
                        createGovernedRoute(
                                definition.getType(),
                                definition.getBaseUrl(),
                                definition.getApiKey(),
                                definition.getName(),
                                definition.getFallbacks(),
                                cfg.getTraffic());
                catalogRoutes.add(
                        route(
                                definition.getId(),
                                definition.getDisplayName(),
                                definition.getName(),
                                definition.getContextWindowTokens(),
                                definition.getMaxOutputTokens(),
                                definition.getSafetyMarginTokens(),
                                cfg.getDefaultId().equals(definition.getId()),
                                routeModel));
            }
        }
        ModelCatalog catalog = new ModelCatalog(cfg.getDefaultId(), catalogRoutes);
        log.info(
                "Configured selectable models: default={} models={}",
                catalog.getDefaultId(),
                catalog.getOptions().stream().map(ModelCatalog.ModelOption::id).toList());
        return catalog;
    }

    private Model createGovernedRoute(
            String type,
            String baseUrl,
            String apiKey,
            String name,
            List<SaasProperties.ModelEndpoint> fallbacks,
            SaasProperties.ModelTraffic traffic) {
        Model primary = create(type, baseUrl, apiKey, name);
        if (!traffic.isEnabled() || isLocalModel(type)) {
            return primary;
        }
        List<Model> routes = new ArrayList<>();
        routes.add(primary);
        for (SaasProperties.ModelEndpoint fallback : fallbacks) {
            routes.add(
                    create(
                            fallback.getType(),
                            fallback.getBaseUrl(),
                            fallback.getApiKey(),
                            fallback.getName()));
        }
        ResilientModel.Policy policy =
                new ResilientModel.Policy(
                        traffic.getMaxConcurrent(),
                        traffic.getMaxQueriesPerMinute(),
                        Duration.ofSeconds(traffic.getAcquireTimeoutSeconds()),
                        Duration.ofSeconds(traffic.getRateLimitCooldownSeconds()),
                        traffic.getCircuitFailureThreshold(),
                        Duration.ofSeconds(traffic.getCircuitOpenSeconds()));
        log.info(
                "Model traffic governance enabled: routes={} maxConcurrent={} maxQpm={}",
                routes.stream().map(Model::getModelName).toList(),
                traffic.getMaxConcurrent(),
                traffic.getMaxQueriesPerMinute());
        return new ResilientModel(routes, policy);
    }

    private static ModelCatalog.Route route(
            String id,
            String displayName,
            String configuredModelName,
            int contextWindowTokens,
            int maxOutputTokens,
            int safetyMarginTokens,
            boolean defaultModel,
            Model model) {
        String label =
                displayName != null && !displayName.isBlank()
                        ? displayName
                        : configuredModelName != null && !configuredModelName.isBlank()
                                ? configuredModelName
                                : id;
        ModelContextProfile profile =
                new ModelContextProfile(
                        id, contextWindowTokens, maxOutputTokens, safetyMarginTokens);
        ModelCatalog.ModelOption option =
                new ModelCatalog.ModelOption(
                        id,
                        label,
                        configuredModelName != null ? configuredModelName : model.getModelName(),
                        contextWindowTokens,
                        maxOutputTokens,
                        defaultModel);
        return new ModelCatalog.Route(option, profile, model);
    }

    private Model create(String configuredType, String baseUrl, String apiKey, String name) {
        String type = configuredType == null ? "stub" : configuredType.toLowerCase();
        switch (type) {
            case "gateway" -> {
                log.info(
                        "Using OpenAI-compatible model gateway: baseUrl={} model={}",
                        baseUrl,
                        name);
                return OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(name)
                        .stream(true)
                        .build();
            }
            case "dashscope" -> {
                log.info("Using DashScope model: {}", name);
                return DashScopeChatModel.builder().apiKey(apiKey).modelName(name).stream(true)
                        .build();
            }
            case "scripted" -> {
                log.info("Using scripted tool-driving model (dev verification, no external LLM)");
                return new ScriptedToolModel();
            }
            default -> {
                log.info("Using stub echo model (no external LLM)");
                return new StubChatModel();
            }
        }
    }

    private static boolean isLocalModel(String configuredType) {
        String type = configuredType == null ? "stub" : configuredType.toLowerCase();
        return "stub".equals(type) || "scripted".equals(type);
    }
}
