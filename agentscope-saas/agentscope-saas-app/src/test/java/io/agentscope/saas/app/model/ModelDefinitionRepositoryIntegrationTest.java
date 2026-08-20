/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** H2/Flyway contract for the DDD model-definition repository adapter. */
@SpringBootTest
@ActiveProfiles("local")
class ModelDefinitionRepositoryIntegrationTest {

    @Autowired ModelDefinitionRepository definitions;
    @Autowired ModelManagementService management;
    @Autowired ModelCatalog catalog;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void createsUpdatesClearsDefaultAndDeletesThroughMyBatis() {
        UUID orgId = UUID.randomUUID();
        TenantContextHolder.setOrgId(orgId.toString());
        ModelDefinitionEntity first = definition(orgId, "managed-small", true);
        ModelDefinitionEntity second = definition(orgId, "managed-large", false);

        definitions.save(first);
        definitions.save(second);
        assertThat(definitions.findByOrgIdOrderByModelId(orgId))
                .extracting(ModelDefinitionEntity::getModelId)
                .containsExactly("managed-large", "managed-small");

        second.setDisplayName("Managed Large Updated");
        second.setUpdatedAt(OffsetDateTime.now());
        definitions.save(second);
        assertThat(second.getVersion()).isEqualTo(1);
        assertThat(definitions.findByOrgIdAndModelId(orgId, second.getModelId()))
                .get()
                .extracting(ModelDefinitionEntity::getDisplayName)
                .isEqualTo("Managed Large Updated");

        definitions.clearDefault(orgId, second.getId());
        assertThat(definitions.findByOrgIdAndModelId(orgId, first.getModelId()))
                .get()
                .extracting(ModelDefinitionEntity::isDefaultModel)
                .isEqualTo(false);
        assertThat(definitions.deleteByOrgIdAndModelId(orgId, second.getModelId())).isEqualTo(1);
        assertThat(definitions.findByOrgIdAndModelId(orgId, second.getModelId())).isEmpty();
    }

    @Test
    void managesEncryptedModelsAndActivatesCatalogAfterCommit() {
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TenantContextHolder.setOrgId(orgId.toString());

        ModelManagementService.ModelView created =
                management.create(
                        orgId,
                        actorId,
                        command("managed-runtime", "Managed Runtime", "runtime-secret", true, 0));

        assertThat(created.defaultModel()).isTrue();
        assertThat(catalog.getDefaultId(orgId)).isEqualTo("managed-runtime");
        assertThat(catalog.requireOption(orgId, "managed-runtime").contextWindowTokens())
                .isEqualTo(32_768);
        ModelDefinitionEntity stored =
                definitions.findByOrgIdAndModelId(orgId, "managed-runtime").orElseThrow();
        assertThat(stored.getApiKeyCiphertext()).isNotBlank().doesNotContain("runtime-secret");

        ModelManagementService.ModelView updated =
                management.update(
                        orgId,
                        actorId,
                        "managed-runtime",
                        command("managed-runtime", "Managed Runtime v2", null, true, 0));
        assertThat(updated.displayName()).isEqualTo("Managed Runtime v2");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(definitions.findByOrgIdAndModelId(orgId, "managed-runtime"))
                .get()
                .extracting(ModelDefinitionEntity::getApiKeyCiphertext)
                .isEqualTo(stored.getApiKeyCiphertext());

        management.delete(orgId, actorId, "managed-runtime");
        assertThat(definitions.findByOrgIdAndModelId(orgId, "managed-runtime")).isEmpty();
        assertThat(catalog.getDefaultId(orgId)).isEqualTo(catalog.getDefaultId());
    }

    private static ModelManagementService.ModelCommand command(
            String id, String displayName, String apiKey, boolean defaultModel, long version) {
        return new ModelManagementService.ModelCommand(
                id,
                displayName,
                "gateway",
                "http://models.test/v1",
                apiKey,
                false,
                "managed-model",
                32_768,
                4_096,
                1_024,
                true,
                defaultModel,
                version);
    }

    private static ModelDefinitionEntity definition(
            UUID orgId, String modelId, boolean defaultModel) {
        ModelDefinitionEntity entity = new ModelDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setModelId(modelId);
        entity.setDisplayName(modelId);
        entity.setProviderType("gateway");
        entity.setBaseUrl("http://models.test/v1");
        entity.setModelName(modelId);
        entity.setContextWindowTokens(32_768);
        entity.setMaxOutputTokens(4_096);
        entity.setSafetyMarginTokens(1_024);
        entity.setEnabled(true);
        entity.setDefaultModel(defaultModel);
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}
