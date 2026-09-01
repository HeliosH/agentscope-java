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

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-time configuration for the optional internal ClawSentry security gateway. */
@ConfigurationProperties(prefix = "saas.security.clawsentry")
public class ClawSentryProperties {

    private boolean enabled;
    private String baseUrl = "http://localhost:8080";
    private String apiPath = "/ahp";
    private String apiToken;
    private int connectTimeoutMillis = 250;
    private int decisionTimeoutMillis = 1500;
    private String sourceFramework = "agentscope-java";
    private String decisionTier = "L1";
    private FailureMode failureMode = FailureMode.ASK;
    private boolean auditEnabled = true;

    public enum FailureMode {
        ASK,
        DENY,
        ALLOW_READ_ONLY
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getDecisionTimeoutMillis() {
        return decisionTimeoutMillis;
    }

    public void setDecisionTimeoutMillis(int decisionTimeoutMillis) {
        this.decisionTimeoutMillis = decisionTimeoutMillis;
    }

    public String getSourceFramework() {
        return sourceFramework;
    }

    public void setSourceFramework(String sourceFramework) {
        this.sourceFramework = sourceFramework;
    }

    public String getDecisionTier() {
        return decisionTier;
    }

    public void setDecisionTier(String decisionTier) {
        this.decisionTier = decisionTier;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
