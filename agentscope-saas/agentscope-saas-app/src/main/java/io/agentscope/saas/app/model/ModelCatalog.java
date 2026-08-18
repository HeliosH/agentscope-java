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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/** Deploy-time model catalog and per-request router. */
public final class ModelCatalog implements Model, ContextWindowAwareModel {

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

    private final String defaultId;
    private final Map<String, Route> routes;
    private final List<ModelOption> options;

    public ModelCatalog(String defaultId, List<Route> configuredRoutes) {
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
        this.defaultId = defaultId;
        this.routes = Map.copyOf(indexed);
        this.options = indexed.values().stream().map(Route::option).toList();
    }

    public String getDefaultId() {
        return defaultId;
    }

    public List<ModelOption> getOptions() {
        return options;
    }

    public ModelOption requireOption(String requestedId) {
        return resolveRoute(requestedId).option();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Route route = resolveRoute(selectedId(messages));
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
        return routes.values().stream()
                .allMatch(route -> route.model().supportsNativeStructuredOutput());
    }

    @Override
    public ModelContextProfile resolveContextProfile(List<Msg> messages) {
        return resolveRoute(selectedId(messages)).contextProfile();
    }

    @Override
    public ModelContextProfile resolveContextProfile(RuntimeContext context) {
        Object selected = context != null ? context.get(MODEL_ID_KEY) : null;
        return resolveRoute(selected != null ? selected.toString() : null).contextProfile();
    }

    private Route resolveRoute(String requestedId) {
        String id = requestedId == null || requestedId.isBlank() ? defaultId : requestedId;
        Route route = routes.get(id);
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
        return defaultId;
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
