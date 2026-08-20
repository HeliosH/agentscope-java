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
package io.agentscope.saas.app.model;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextProfile;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ContextWindowExceededException;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/** Deployment catalog plus organization-managed hot-reload routes. */
public final class ModelCatalog implements Model, ContextWindowAwareModel {

    public static final String ORG_ID_KEY = "agentscope.org.id";

    public record ModelOption(
            String id,
            String displayName,
            String modelName,
            int contextWindowTokens,
            int maxOutputTokens,
            boolean defaultModel) {}

    public record Route(ModelOption option, ModelContextProfile contextProfile, Model model) {
        public Route {
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(contextProfile, "contextProfile");
            Objects.requireNonNull(model, "model");
        }
    }

    private record CatalogSnapshot(
            String defaultId, Map<String, Route> routes, List<ModelOption> options) {}

    /** A fully validated catalog snapshot that can be activated after its DB transaction commits. */
    public final class PreparedRefresh {

        private final UUID orgId;
        private final CatalogSnapshot replacement;

        private PreparedRefresh(UUID orgId, CatalogSnapshot replacement) {
            this.orgId = orgId;
            this.replacement = replacement;
        }

        public String defaultId() {
            return replacement.defaultId();
        }

        public void activate() {
            organizationCatalogs.put(orgId, replacement);
        }
    }

    private final CatalogSnapshot deployment;
    private final ModelDefinitionRepository definitions;
    private final ModelCredentialCipher credentialCipher;
    private final ModelRouteFactory routeFactory;
    private final SaasProperties.ModelTraffic traffic;
    private final Map<UUID, CatalogSnapshot> organizationCatalogs = new ConcurrentHashMap<>();

    public ModelCatalog(String defaultId, List<Route> configuredRoutes) {
        this(defaultId, configuredRoutes, null, null, null, null);
    }

    public ModelCatalog(
            String defaultId,
            List<Route> configuredRoutes,
            ModelDefinitionRepository definitions,
            ModelCredentialCipher credentialCipher,
            ModelRouteFactory routeFactory,
            SaasProperties.ModelTraffic traffic) {
        this.deployment = snapshot(defaultId, configuredRoutes);
        this.definitions = definitions;
        this.credentialCipher = credentialCipher;
        this.routeFactory = routeFactory;
        this.traffic = traffic;
    }

    private static CatalogSnapshot snapshot(String defaultId, List<Route> configuredRoutes) {
        if (configuredRoutes == null || configuredRoutes.isEmpty()) {
            throw new IllegalArgumentException("At least one model route is required");
        }
        LinkedHashMap<String, Route> indexed = new LinkedHashMap<>();
        for (Route route : configuredRoutes) {
            String id = route.option().id();
            if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Invalid model id: " + id);
            }
            if (indexed.putIfAbsent(id, route) != null) {
                throw new IllegalArgumentException("Duplicate model id: " + id);
            }
        }
        if (defaultId == null || !indexed.containsKey(defaultId)) {
            throw new IllegalArgumentException(
                    "Default model id is not in the catalog: " + defaultId);
        }
        return withDefault(defaultId, indexed);
    }

    public String getDefaultId() {
        return deployment.defaultId();
    }

    public String getDefaultId(UUID orgId) {
        return catalog(orgId).defaultId();
    }

    public List<ModelOption> getOptions() {
        return deployment.options();
    }

    public List<ModelOption> getOptions(UUID orgId) {
        return catalog(orgId).options();
    }

    public ModelOption requireOption(String requestedId) {
        return resolveRoute(deployment, requestedId).option();
    }

    public ModelOption requireOption(UUID orgId, String requestedId) {
        return resolveRoute(catalog(orgId), requestedId).option();
    }

    /** Rebuilds one organization's immutable snapshot before atomically replacing the cache. */
    public void refresh(UUID orgId) {
        prepareRefresh(orgId).activate();
    }

    /** Builds and validates a snapshot without exposing it to requests yet. */
    public PreparedRefresh prepareRefresh(UUID orgId) {
        Objects.requireNonNull(orgId, "orgId");
        CatalogSnapshot replacement = loadManagedCatalog(orgId);
        return new PreparedRefresh(orgId, replacement);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Route route = resolveRoute(catalog(selectedOrgId(messages)), selectedId(messages));
        int estimatedInputTokens = TokenCounterUtil.calculateToken(messages, tools);
        if (estimatedInputTokens > route.contextProfile().inputTokenBudget()) {
            return Flux.error(
                    new ContextWindowExceededException(
                            "Input is too large for model '"
                                    + route.option().id()
                                    + "' (estimated "
                                    + estimatedInputTokens
                                    + " tokens, input budget "
                                    + route.contextProfile().inputTokenBudget()
                                    + "). Remove large attachments or choose a model with a larger"
                                    + " context window."));
        }
        return route.model().stream(
                messages, tools, capOutput(options, route.contextProfile().maxOutputTokens()));
    }

    @Override
    public String getModelName() {
        return "enterprise-model-router";
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return deployment.routes().values().stream()
                .allMatch(route -> route.model().supportsNativeStructuredOutput());
    }

    @Override
    public ModelContextProfile resolveContextProfile(List<Msg> messages) {
        return resolveRoute(catalog(selectedOrgId(messages)), selectedId(messages))
                .contextProfile();
    }

    @Override
    public ModelContextProfile resolveContextProfile(RuntimeContext context) {
        Object selected = context != null ? context.get(MODEL_ID_KEY) : null;
        return resolveRoute(catalog(orgId(context)), selected != null ? selected.toString() : null)
                .contextProfile();
    }

    private CatalogSnapshot catalog(UUID orgId) {
        if (orgId == null || definitions == null) {
            return deployment;
        }
        return organizationCatalogs.computeIfAbsent(orgId, this::loadManagedCatalog);
    }

    private CatalogSnapshot loadManagedCatalog(UUID orgId) {
        LinkedHashMap<String, Route> merged = new LinkedHashMap<>(deployment.routes());
        String selectedDefault = deployment.defaultId();
        List<ModelDefinitionEntity> managed = definitions.findByOrgIdOrderByModelId(orgId);
        for (ModelDefinitionEntity definition : managed) {
            merged.remove(definition.getModelId());
            if (!definition.isEnabled()) {
                continue;
            }
            String apiKey =
                    credentialCipher.decrypt(
                            definition.getApiKeyCiphertext(), orgId, definition.getModelId());
            Route route = routeFactory.managedRoute(definition, apiKey, traffic);
            merged.put(definition.getModelId(), route);
            if (definition.isDefaultModel()) {
                selectedDefault = definition.getModelId();
            }
        }
        if (merged.isEmpty()) {
            throw new IllegalStateException("Organization model catalog cannot be empty");
        }
        if (!merged.containsKey(selectedDefault)) {
            selectedDefault = merged.keySet().iterator().next();
        }
        return withDefault(selectedDefault, merged);
    }

    private static CatalogSnapshot withDefault(String defaultId, Map<String, Route> routes) {
        LinkedHashMap<String, Route> normalized = new LinkedHashMap<>();
        for (Route route : routes.values()) {
            ModelOption option = route.option();
            ModelOption marked =
                    new ModelOption(
                            option.id(),
                            option.displayName(),
                            option.modelName(),
                            option.contextWindowTokens(),
                            option.maxOutputTokens(),
                            option.id().equals(defaultId));
            normalized.put(option.id(), new Route(marked, route.contextProfile(), route.model()));
        }
        return new CatalogSnapshot(
                defaultId,
                Map.copyOf(normalized),
                normalized.values().stream().map(Route::option).toList());
    }

    private static Route resolveRoute(CatalogSnapshot catalog, String requestedId) {
        String id =
                requestedId == null || requestedId.isBlank() ? catalog.defaultId() : requestedId;
        Route route = catalog.routes().get(id);
        if (route == null) {
            throw new IllegalArgumentException("Unknown or disabled model id: " + id);
        }
        return route;
    }

    private String selectedId(List<Msg> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg message = messages.get(i);
                if (message == null || message.getMetadata() == null) {
                    continue;
                }
                Object selected = message.getMetadata().get(MODEL_ID_KEY);
                if (selected != null && !selected.toString().isBlank()) {
                    return selected.toString();
                }
            }
        }
        return null;
    }

    private UUID selectedOrgId(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message == null || message.getMetadata() == null) {
                continue;
            }
            UUID parsed = parseUuid(message.getMetadata().get(ORG_ID_KEY));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static UUID orgId(RuntimeContext context) {
        if (context == null) {
            return null;
        }
        TenantContext tenant = context.get(TenantContext.class);
        if (tenant != null) {
            return parseUuid(tenant.orgId());
        }
        return parseUuid(context.get(ORG_ID_KEY));
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GenerateOptions capOutput(GenerateOptions options, int maxOutputTokens) {
        GenerateOptions.Builder limit = GenerateOptions.builder();
        if (options != null && options.getMaxCompletionTokens() != null) {
            limit.maxCompletionTokens(Math.min(options.getMaxCompletionTokens(), maxOutputTokens));
        } else {
            int requested =
                    options != null && options.getMaxTokens() != null
                            ? options.getMaxTokens()
                            : maxOutputTokens;
            limit.maxTokens(Math.min(requested, maxOutputTokens));
        }
        return GenerateOptions.mergeOptions(limit.build(), options);
    }
}
