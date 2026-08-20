/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Organization-scoped model endpoint and context-window policy. */
public class ModelDefinitionEntity {

    private UUID id;
    private UUID orgId;
    private String modelId;
    private String displayName;
    private String providerType;
    private String baseUrl;
    private String apiKeyCiphertext;
    private String modelName;
    private int contextWindowTokens;
    private int maxOutputTokens;
    private int safetyMarginTokens;
    private boolean enabled;
    private boolean defaultModel;
    private long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    public void setApiKeyCiphertext(String apiKeyCiphertext) {
        this.apiKeyCiphertext = apiKeyCiphertext;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getSafetyMarginTokens() {
        return safetyMarginTokens;
    }

    public void setSafetyMarginTokens(int safetyMarginTokens) {
        this.safetyMarginTokens = safetyMarginTokens;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(boolean defaultModel) {
        this.defaultModel = defaultModel;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
