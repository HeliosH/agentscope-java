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
package io.agentscope.saas.app.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dev-only authentication bypass configuration. When {@code saas.security.dev.enabled=true}, the
 * platform skips JWT validation on {@code /api/**} and resolves every request to a fixed
 * {@link io.agentscope.saas.core.tenant.TenantContext} built from these properties — so functional
 * verification can run without a login flow.
 *
 * <p>This is a standalone {@code @ConfigurationProperties} class (mirroring {@link JwtProperties})
 * rather than a section of {@code SaasProperties}, keeping the {@code saas.security.*} namespace
 * cohesive.
 *
 * <p><b>Never enable in production.</b>
 */
@ConfigurationProperties(prefix = "saas.security.dev")
public class DevSecurityProperties {

    /** Whether the dev auth bypass is active. Default {@code false}. */
    private boolean enabled = false;

    private String orgId = "dev-org";
    private String userId = "dev-user";
    private String role = "owner";
    private String tier = "privileged";
    private int maxSandboxes = 5;
    private long tokenQuota = Long.MAX_VALUE;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public int getMaxSandboxes() {
        return maxSandboxes;
    }

    public void setMaxSandboxes(int maxSandboxes) {
        this.maxSandboxes = maxSandboxes;
    }

    public long getTokenQuota() {
        return tokenQuota;
    }

    public void setTokenQuota(long tokenQuota) {
        this.tokenQuota = tokenQuota;
    }
}
