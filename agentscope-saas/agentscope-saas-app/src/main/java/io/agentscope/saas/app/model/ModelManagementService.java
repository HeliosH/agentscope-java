/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Administrator use cases for organization-managed model endpoints. */
@Service
public class ModelManagementService {

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> PROVIDERS = Set.of("gateway", "dashscope");

    public record ModelView(
            String id,
            String displayName,
            String providerType,
            String baseUrl,
            String modelName,
            int contextWindowTokens,
            int maxOutputTokens,
            int safetyMarginTokens,
            boolean enabled,
            boolean defaultModel,
            boolean apiKeyConfigured,
            String source,
            long version,
            OffsetDateTime updatedAt) {}

    public record ModelCommand(
            String id,
            String displayName,
            String providerType,
            String baseUrl,
            String apiKey,
            boolean clearApiKey,
            String modelName,
            Integer contextWindowTokens,
            Integer maxOutputTokens,
            Integer safetyMarginTokens,
            Boolean enabled,
            Boolean defaultModel,
            Long version) {}

    public record TestResult(boolean ok, String message, long latencyMs) {}

    private final ModelDefinitionRepository definitions;
    private final ModelCredentialCipher credentialCipher;
    private final ModelRouteFactory routeFactory;
    private final ModelCatalog catalog;
    private final SaasProperties properties;
    private final AuditService audit;

    public ModelManagementService(
            ModelDefinitionRepository definitions,
            ModelCredentialCipher credentialCipher,
            ModelRouteFactory routeFactory,
            ModelCatalog catalog,
            SaasProperties properties,
            AuditService audit) {
        this.definitions = definitions;
        this.credentialCipher = credentialCipher;
        this.routeFactory = routeFactory;
        this.catalog = catalog;
        this.properties = properties;
        this.audit = audit;
    }

    public List<ModelView> list(UUID orgId) {
        List<ModelDefinitionEntity> managed = definitions.findByOrgIdOrderByModelId(orgId);
        catalog.refresh(orgId);
        Map<String, ModelView> views = new LinkedHashMap<>();
        String effectiveDefault = catalog.getDefaultId(orgId);
        for (ModelCatalog.ModelOption option : catalog.getOptions()) {
            views.put(
                    option.id(),
                    new ModelView(
                            option.id(),
                            option.displayName(),
                            "deployment",
                            null,
                            option.modelName(),
                            option.contextWindowTokens(),
                            option.maxOutputTokens(),
                            0,
                            true,
                            option.id().equals(effectiveDefault),
                            false,
                            "deployment",
                            0,
                            null));
        }
        for (ModelDefinitionEntity definition : managed) {
            views.put(definition.getModelId(), toView(definition, effectiveDefault));
        }
        return List.copyOf(views.values());
    }

    @Transactional
    public ModelView create(UUID orgId, UUID actorId, ModelCommand command) {
        Validated input = validate(command, null);
        if (definitions.findByOrgIdAndModelId(orgId, input.id()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "model id already exists");
        }
        ModelDefinitionEntity entity = new ModelDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setModelId(input.id());
        apply(entity, input, false);
        if (entity.isDefaultModel()) {
            definitions.clearDefault(orgId, entity.getId());
        }
        ModelDefinitionEntity saved = definitions.save(entity);
        ModelCatalog.PreparedRefresh refresh = prepareRefreshOrFail(orgId);
        activateAfterCommit(refresh);
        audit.record(
                orgId,
                actorId,
                "admin.model.create",
                "model:" + saved.getModelId(),
                Map.of("provider", saved.getProviderType(), "enabled", saved.isEnabled()));
        return toView(saved, refresh.defaultId());
    }

    @Transactional
    public ModelView update(UUID orgId, UUID actorId, String modelId, ModelCommand command) {
        ModelDefinitionEntity entity =
                definitions
                        .findByOrgIdAndModelId(orgId, modelId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "managed model not found; deployment models are"
                                                        + " read-only"));
        Validated input = validate(command, modelId);
        if (command.version() != null && command.version() != entity.getVersion()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "model was changed by another administrator");
        }
        apply(entity, input, true);
        if (entity.isDefaultModel()) {
            definitions.clearDefault(orgId, entity.getId());
        }
        ModelDefinitionEntity saved = definitions.save(entity);
        ModelCatalog.PreparedRefresh refresh = prepareRefreshOrFail(orgId);
        activateAfterCommit(refresh);
        audit.record(
                orgId,
                actorId,
                "admin.model.update",
                "model:" + saved.getModelId(),
                Map.of("provider", saved.getProviderType(), "enabled", saved.isEnabled()));
        return toView(saved, refresh.defaultId());
    }

    @Transactional
    public void delete(UUID orgId, UUID actorId, String modelId) {
        ModelDefinitionEntity existing =
                definitions
                        .findByOrgIdAndModelId(orgId, modelId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "managed model not found; deployment models are"
                                                        + " read-only"));
        if (definitions.deleteByOrgIdAndModelId(orgId, modelId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "model could not be deleted");
        }
        ModelCatalog.PreparedRefresh refresh = prepareRefreshOrFail(orgId);
        activateAfterCommit(refresh);
        audit.record(
                orgId,
                actorId,
                "admin.model.delete",
                "model:" + modelId,
                Map.of("wasDefault", existing.isDefaultModel()));
    }

    public TestResult test(UUID orgId, UUID actorId, String modelId) {
        ModelDefinitionEntity definition =
                definitions
                        .findByOrgIdAndModelId(orgId, modelId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "managed model not found; deployment models are"
                                                        + " read-only"));
        long started = System.nanoTime();
        boolean ok = false;
        String message;
        try {
            String apiKey =
                    credentialCipher.decrypt(
                            definition.getApiKeyCiphertext(), orgId, definition.getModelId());
            ModelCatalog.Route route =
                    routeFactory.managedRoute(
                            definition, apiKey, properties.getModel().getTraffic());
            Msg probe =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("model-connection-test")
                            .textContent("Reply with OK.")
                            .build();
            route.model().stream(
                            List.of(probe),
                            List.of(),
                            GenerateOptions.builder().maxTokens(8).build())
                    .timeout(
                            Duration.ofSeconds(
                                    Math.max(
                                            1,
                                            properties
                                                    .getModel()
                                                    .getManagement()
                                                    .getTestTimeoutSeconds())))
                    .collectList()
                    .block();
            ok = true;
            message = "Connection succeeded";
        } catch (RuntimeException e) {
            message = safeError(e);
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        audit.record(
                orgId,
                actorId,
                "admin.model.test",
                "model:" + modelId,
                Map.of("ok", ok, "latencyMs", latencyMs));
        return new TestResult(ok, message, latencyMs);
    }

    private void apply(ModelDefinitionEntity entity, Validated input, boolean existing) {
        entity.setDisplayName(input.displayName());
        entity.setProviderType(input.providerType());
        entity.setBaseUrl(input.baseUrl());
        entity.setModelName(input.modelName());
        entity.setContextWindowTokens(input.contextWindowTokens());
        entity.setMaxOutputTokens(input.maxOutputTokens());
        entity.setSafetyMarginTokens(input.safetyMarginTokens());
        entity.setEnabled(input.enabled());
        entity.setDefaultModel(input.defaultModel() && input.enabled());
        if (input.clearApiKey()) {
            entity.setApiKeyCiphertext(null);
        } else if (input.apiKey() != null) {
            entity.setApiKeyCiphertext(
                    credentialCipher.encrypt(
                            input.apiKey(), entity.getOrgId(), entity.getModelId()));
        } else if (!existing) {
            entity.setApiKeyCiphertext(null);
        }
        entity.setUpdatedAt(OffsetDateTime.now());

        // Build before persistence so invalid provider configuration cannot poison the hot catalog.
        String testKey =
                input.apiKey() != null
                        ? input.apiKey()
                        : credentialCipher.decrypt(
                                entity.getApiKeyCiphertext(),
                                entity.getOrgId(),
                                entity.getModelId());
        routeFactory.managedRoute(entity, testKey, properties.getModel().getTraffic());
    }

    private Validated validate(ModelCommand command, String pathModelId) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String id = trim(pathModelId != null ? pathModelId : command.id());
        if (id == null || !MODEL_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid model id");
        }
        if (pathModelId != null
                && command.id() != null
                && !pathModelId.equals(command.id().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model id cannot be changed");
        }
        String displayName = required(command.displayName(), "displayName", 128);
        String provider =
                required(command.providerType(), "providerType", 24).toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported model provider");
        }
        String modelName = required(command.modelName(), "modelName", 255);
        String baseUrl = trim(command.baseUrl());
        if ("gateway".equals(provider)) {
            validateBaseUrl(baseUrl);
        }
        int context = positive(command.contextWindowTokens(), "contextWindowTokens");
        int output = positive(command.maxOutputTokens(), "maxOutputTokens");
        int safety = nonNegative(command.safetyMarginTokens(), "safetyMarginTokens");
        if (context <= output + safety) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "context window must be larger than output plus safety margin");
        }
        String apiKey = trim(command.apiKey());
        if (apiKey != null && command.clearApiKey()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "apiKey and clearApiKey cannot both be set");
        }
        return new Validated(
                id,
                displayName,
                provider,
                baseUrl,
                apiKey,
                command.clearApiKey(),
                modelName,
                context,
                output,
                safety,
                command.enabled() == null || command.enabled(),
                Boolean.TRUE.equals(command.defaultModel()));
    }

    private ModelCatalog.PreparedRefresh prepareRefreshOrFail(UUID orgId) {
        try {
            return catalog.prepareRefresh(orgId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "model configuration cannot be activated", e);
        }
    }

    private static void activateAfterCommit(ModelCatalog.PreparedRefresh refresh) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refresh.activate();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        refresh.activate();
                    }
                });
    }

    private static void validateBaseUrl(String value) {
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "baseUrl is required for gateway models");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("scheme");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("host");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "baseUrl must be an HTTP(S) URL without credentials");
        }
    }

    private static ModelView toView(ModelDefinitionEntity entity, String effectiveDefault) {
        return new ModelView(
                entity.getModelId(),
                entity.getDisplayName(),
                entity.getProviderType(),
                entity.getBaseUrl(),
                entity.getModelName(),
                entity.getContextWindowTokens(),
                entity.getMaxOutputTokens(),
                entity.getSafetyMarginTokens(),
                entity.isEnabled(),
                entity.getModelId().equals(effectiveDefault),
                entity.getApiKeyCiphertext() != null && !entity.getApiKeyCiphertext().isBlank(),
                "managed",
                entity.getVersion(),
                entity.getUpdatedAt());
    }

    private static String required(String value, String field, int max) {
        String normalized = trim(value);
        if (normalized == null || normalized.length() > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " is required and must be at most " + max);
        }
        return normalized;
    }

    private static int positive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return value;
    }

    private static int nonNegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " must not be negative");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = trim(cause.getMessage());
        if (message == null) {
            message = cause.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private record Validated(
            String id,
            String displayName,
            String providerType,
            String baseUrl,
            String apiKey,
            boolean clearApiKey,
            String modelName,
            int contextWindowTokens,
            int maxOutputTokens,
            int safetyMarginTokens,
            boolean enabled,
            boolean defaultModel) {}
}
