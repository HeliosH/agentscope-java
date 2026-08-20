/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.model.AuditLogEntity;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.domain.repository.AuditLogRepository;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
import io.agentscope.saas.model.StubChatModel;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelManagementServiceTest {

    @Test
    void testsConfiguredModelAndRecordsSafeAuditResult() {
        UUID orgId = UUID.randomUUID();
        ModelDefinitionEntity definition = definition(orgId);
        CapturingAuditRepository audits = new CapturingAuditRepository();
        SaasProperties properties = new SaasProperties();
        properties.getModel().getManagement().setTestTimeoutSeconds(2);
        ModelRouteFactory routeFactory =
                new ModelRouteFactory() {
                    @Override
                    public ModelCatalog.Route managedRoute(
                            ModelDefinitionEntity ignored,
                            String apiKey,
                            SaasProperties.ModelTraffic traffic) {
                        return route(
                                definition.getModelId(),
                                definition.getDisplayName(),
                                definition.getModelName(),
                                definition.getContextWindowTokens(),
                                definition.getMaxOutputTokens(),
                                definition.getSafetyMarginTokens(),
                                false,
                                new StubChatModel());
                    }
                };
        ModelDefinitionRepository definitions = new SingleModelRepository(definition);
        ModelCatalog catalog =
                new ModelCatalog(
                        "default",
                        List.of(
                                routeFactory.route(
                                        "default",
                                        "Default",
                                        "stub",
                                        8_192,
                                        512,
                                        512,
                                        true,
                                        new StubChatModel())));
        ModelManagementService service =
                new ModelManagementService(
                        definitions,
                        new ModelCredentialCipher(properties),
                        routeFactory,
                        catalog,
                        properties,
                        new AuditService(audits, new ObjectMapper()));

        ModelManagementService.TestResult result =
                service.test(orgId, UUID.randomUUID(), definition.getModelId());

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("Connection succeeded");
        assertThat(audits.saved.getAction()).isEqualTo("admin.model.test");
        assertThat(audits.saved.getDetail()).contains("\"ok\":true").doesNotContain("secret");
    }

    private static ModelDefinitionEntity definition(UUID orgId) {
        ModelDefinitionEntity entity = new ModelDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setModelId("probe-model");
        entity.setDisplayName("Probe Model");
        entity.setProviderType("gateway");
        entity.setBaseUrl("http://models.test/v1");
        entity.setModelName("probe-model");
        entity.setContextWindowTokens(8_192);
        entity.setMaxOutputTokens(512);
        entity.setSafetyMarginTokens(512);
        entity.setEnabled(true);
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }

    private static final class SingleModelRepository implements ModelDefinitionRepository {
        private final ModelDefinitionEntity definition;

        private SingleModelRepository(ModelDefinitionEntity definition) {
            this.definition = definition;
        }

        @Override
        public List<ModelDefinitionEntity> findByOrgIdOrderByModelId(UUID orgId) {
            return List.of(definition);
        }

        @Override
        public Optional<ModelDefinitionEntity> findByOrgIdAndModelId(UUID orgId, String modelId) {
            return definition.getOrgId().equals(orgId) && definition.getModelId().equals(modelId)
                    ? Optional.of(definition)
                    : Optional.empty();
        }

        @Override
        public ModelDefinitionEntity save(ModelDefinitionEntity value) {
            return value;
        }

        @Override
        public void clearDefault(UUID orgId, UUID exceptId) {}

        @Override
        public long deleteByOrgIdAndModelId(UUID orgId, String modelId) {
            return 0;
        }
    }

    private static final class CapturingAuditRepository implements AuditLogRepository {
        private AuditLogEntity saved;

        @Override
        public AuditLogEntity save(AuditLogEntity event) {
            saved = event;
            return event;
        }

        @Override
        public List<AuditLogEntity> findAdminAuditLogs(
                UUID orgId, UUID actor, String action, String resourcePrefix, int limit) {
            return List.of();
        }
    }
}
