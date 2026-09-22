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
package io.agentscope.saas.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-time configuration for the optional internal LlamaFirewall service. */
@ConfigurationProperties(prefix = "saas.security.llama-firewall")
public class LlamaFirewallProperties {

    private boolean enabled;
    private String baseUrl = "http://localhost:18082";
    private String scanPath = "/v1/scan";
    private String apiToken;
    private int connectTimeoutMillis = 250;
    private int decisionTimeoutMillis = 2000;
    private int maxContentChars = 100_000;
    private FailureMode failureMode = FailureMode.LOCAL_GUARD_ONLY;
    private int circuitFailureThreshold = 3;
    private long circuitOpenDurationMillis = 30_000;
    private boolean auditEnabled = true;

    public enum FailureMode {
        LOCAL_GUARD_ONLY,
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

    public String getScanPath() {
        return scanPath;
    }

    public void setScanPath(String scanPath) {
        this.scanPath = scanPath;
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

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenDurationMillis() {
        return circuitOpenDurationMillis;
    }

    public void setCircuitOpenDurationMillis(long circuitOpenDurationMillis) {
        this.circuitOpenDurationMillis = circuitOpenDurationMillis;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
