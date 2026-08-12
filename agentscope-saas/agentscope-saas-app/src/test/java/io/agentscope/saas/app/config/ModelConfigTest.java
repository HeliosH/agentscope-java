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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ResilientModel;
import io.agentscope.saas.model.ScriptedToolModel;
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

        Model model = config.chatModel(properties);

        assertInstanceOf(ResilientModel.class, model);
        assertEquals("primary-model+failover", model.getModelName());
    }

    @Test
    void leavesDeterministicDevModelUnwrapped() {
        SaasProperties properties = new SaasProperties();
        properties.getModel().setType("scripted");

        Model model = config.chatModel(properties);

        assertInstanceOf(ScriptedToolModel.class, model);
    }
}
