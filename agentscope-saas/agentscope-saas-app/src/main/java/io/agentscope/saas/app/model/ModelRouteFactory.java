/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ResilientModel;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.model.ScriptedToolModel;
import io.agentscope.saas.model.StubChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Builds governed runtime model routes from deployment or managed definitions. */
@Component
public class ModelRouteFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelRouteFactory.class);

    public ModelCatalog.Route managedRoute(
            ModelDefinitionEntity definition, String apiKey, SaasProperties.ModelTraffic traffic) {
        Model model =
                createGovernedRoute(
                        definition.getProviderType(),
                        definition.getBaseUrl(),
                        apiKey,
                        definition.getModelName(),
                        List.of(),
                        traffic);
        return route(
                definition.getModelId(),
                definition.getDisplayName(),
                definition.getModelName(),
                definition.getContextWindowTokens(),
                definition.getMaxOutputTokens(),
                definition.getSafetyMarginTokens(),
                definition.isDefaultModel(),
                model);
    }

    public Model createGovernedRoute(
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
        return new ResilientModel(routes, policy);
    }

    public ModelCatalog.Route route(
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

    public Model create(String configuredType, String baseUrl, String apiKey, String name) {
        String type = configuredType == null ? "stub" : configuredType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "gateway" ->
                    OpenAIChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(name)
                            .stream(true)
                            .build();
            case "dashscope" ->
                    DashScopeChatModel.builder().apiKey(apiKey).modelName(name).stream(true)
                            .build();
            case "scripted" -> new ScriptedToolModel();
            case "stub" -> new StubChatModel();
            default -> throw new IllegalArgumentException("Unsupported model provider: " + type);
        };
    }

    private static boolean isLocalModel(String configuredType) {
        String type = configuredType == null ? "stub" : configuredType.toLowerCase(Locale.ROOT);
        return "stub".equals(type) || "scripted".equals(type);
    }
}
