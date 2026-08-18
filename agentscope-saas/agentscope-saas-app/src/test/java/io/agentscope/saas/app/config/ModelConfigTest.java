/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ContextWindowAwareModel;
import io.agentscope.saas.app.model.ModelCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

    private final ModelConfig config = new ModelConfig();

    @Test
    void wrapsRemoteRoutesWithDeploymentTrafficGovernance() {
        SaasProperties properties = new SaasProperties();
        properties.getModel().setType("gateway");
        properties.getModel().setBaseUrl("http://primary.test/v1");
        properties.getModel().setApiKey("primary-secret");
        properties.getModel().setName("primary-model");
        SaasProperties.ModelEndpoint fallback = new SaasProperties.ModelEndpoint();
        fallback.setType("gateway");
        fallback.setBaseUrl("http://fallback.test/v1");
        fallback.setApiKey("fallback-secret");
        fallback.setName("fallback-model");
        properties.getModel().setFallbacks(List.of(fallback));

        ModelCatalog model = config.chatModel(properties);

        assertEquals("default", model.getDefaultId());
        assertEquals("primary-model", model.getOptions().get(0).modelName());
        assertEquals(
                26_880, model.resolveContextProfile(RuntimeContext.empty()).inputTokenBudget());
    }

    @Test
    void leavesDeterministicDevModelUnwrapped() {
        SaasProperties properties = new SaasProperties();
        properties.getModel().setType("scripted");

        ModelCatalog model = config.chatModel(properties);

        assertEquals("default", model.getDefaultId());
        assertEquals(1, model.getOptions().size());
    }

    @Test
    void createsSelectableCatalogAndRejectsUnknownModel() {
        SaasProperties properties = new SaasProperties();
        properties.getModel().setDefaultId("small");
        SaasProperties.ModelDefinition small = definition("small", 8_192, 1_024);
        SaasProperties.ModelDefinition large = definition("large", 131_072, 8_192);
        properties.getModel().setCatalog(List.of(small, large));

        ModelCatalog model = config.chatModel(properties);

        assertEquals(
                List.of("small", "large"),
                model.getOptions().stream().map(ModelCatalog.ModelOption::id).toList());
        RuntimeContext smallContext =
                RuntimeContext.builder().put(ContextWindowAwareModel.MODEL_ID_KEY, "small").build();
        assertEquals(6_144, model.resolveContextProfile(smallContext).inputTokenBudget());
        assertThrows(IllegalArgumentException.class, () -> model.requireOption("missing"));
    }

    private static SaasProperties.ModelDefinition definition(
            String id, int contextWindowTokens, int maxOutputTokens) {
        SaasProperties.ModelDefinition definition = new SaasProperties.ModelDefinition();
        definition.setId(id);
        definition.setDisplayName(id);
        definition.setType("stub");
        definition.setName(id + "-model");
        definition.setContextWindowTokens(contextWindowTokens);
        definition.setMaxOutputTokens(maxOutputTokens);
        definition.setSafetyMarginTokens(1_024);
        return definition;
    }
}
