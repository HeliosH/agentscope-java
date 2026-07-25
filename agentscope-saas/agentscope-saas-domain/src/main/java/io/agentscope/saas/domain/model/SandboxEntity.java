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
package io.agentscope.saas.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks active sandbox instances per tenant/user for quota enforcement and operational visibility.
 * The framework's {@code SessionSandboxStateStore} persists sandbox <em>state</em> (for resume)
 * via {@code AgentStateStore} (Redis/JDBC); this entity tracks the operational lifecycle (who owns
 * which sandbox, when it expires).
 */
public class SandboxEntity {

    private UUID id;

    private UUID orgId;

    private UUID userId;

    private UUID agentId;

    private String sessionId;

    private String sandboxType = "docker";

    private String externalId;

    private String status = "active";

    private OffsetDateTime createdAt;

    private OffsetDateTime lastUsedAt;

    private OffsetDateTime expiresAt;

    private String backendReleaseStatus;

    private int backendReleaseAttempts;

    private OffsetDateTime backendReleasedAt;

    private String backendReleaseError;

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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSandboxType() {
        return sandboxType;
    }

    public void setSandboxType(String sandboxType) {
        this.sandboxType = sandboxType;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(OffsetDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getBackendReleaseStatus() {
        return backendReleaseStatus;
    }

    public void setBackendReleaseStatus(String backendReleaseStatus) {
        this.backendReleaseStatus = backendReleaseStatus;
    }

    public int getBackendReleaseAttempts() {
        return backendReleaseAttempts;
    }

    public void setBackendReleaseAttempts(int backendReleaseAttempts) {
        this.backendReleaseAttempts = backendReleaseAttempts;
    }

    public OffsetDateTime getBackendReleasedAt() {
        return backendReleasedAt;
    }

    public void setBackendReleasedAt(OffsetDateTime backendReleasedAt) {
        this.backendReleasedAt = backendReleasedAt;
    }

    public String getBackendReleaseError() {
        return backendReleaseError;
    }

    public void setBackendReleaseError(String backendReleaseError) {
        this.backendReleaseError = backendReleaseError;
    }
}
