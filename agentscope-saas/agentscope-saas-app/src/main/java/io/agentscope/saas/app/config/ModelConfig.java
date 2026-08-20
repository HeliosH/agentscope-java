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
import io.agentscope.saas.app.model.ModelCatalog;
import io.agentscope.saas.app.model.ModelCredentialCipher;
import io.agentscope.saas.app.model.ModelRouteFactory;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
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
    public ModelCatalog chatModel(
            SaasProperties properties,
            ModelDefinitionRepository definitions,
            ModelCredentialCipher credentialCipher,
            ModelRouteFactory routeFactory) {
        SaasProperties.Model cfg = properties.getModel();
        List<ModelCatalog.Route> catalogRoutes = new ArrayList<>();
        if (cfg.getCatalog().isEmpty()) {
            Model routeModel =
                    routeFactory.createGovernedRoute(
                            cfg.getType(),
                            cfg.getBaseUrl(),
                            cfg.getApiKey(),
                            cfg.getName(),
                            cfg.getFallbacks(),
                            cfg.getTraffic());
            catalogRoutes.add(
                    routeFactory.route(
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
                        routeFactory.createGovernedRoute(
                                definition.getType(),
                                definition.getBaseUrl(),
                                definition.getApiKey(),
                                definition.getName(),
                                definition.getFallbacks(),
                                cfg.getTraffic());
                catalogRoutes.add(
                        routeFactory.route(
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
        ModelCatalog catalog =
                new ModelCatalog(
                        cfg.getDefaultId(),
                        catalogRoutes,
                        definitions,
                        credentialCipher,
                        routeFactory,
                        cfg.getTraffic());
        log.info(
                "Configured selectable models: default={} models={}",
                catalog.getDefaultId(),
                catalog.getOptions().stream().map(ModelCatalog.ModelOption::id).toList());
        return catalog;
    }
}
