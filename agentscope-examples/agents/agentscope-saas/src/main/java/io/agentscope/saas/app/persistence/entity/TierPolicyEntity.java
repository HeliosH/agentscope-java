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
package io.agentscope.saas.app.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Quota policy associated with a user tier (replaces billing subscriptions; no charging). */
@Entity
@Table(name = "tier_policies")
public class TierPolicyEntity {

    @Id
    @Column(name = "tier")
    private String tier;

    @Column(name = "max_agents")
    private Integer maxAgents;

    @Column(name = "max_sandboxes")
    private Integer maxSandboxes;

    @Column(name = "monthly_token_quota")
    private Long monthlyTokenQuota;

    @Column(name = "storage_gb")
    private Integer storageGb;

    @Column(name = "idle_ttl_seconds")
    private Integer idleTtlSeconds;

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public Integer getMaxAgents() {
        return maxAgents;
    }

    public void setMaxAgents(Integer maxAgents) {
        this.maxAgents = maxAgents;
    }

    public Integer getMaxSandboxes() {
        return maxSandboxes;
    }

    public void setMaxSandboxes(Integer maxSandboxes) {
        this.maxSandboxes = maxSandboxes;
    }

    public Long getMonthlyTokenQuota() {
        return monthlyTokenQuota;
    }

    public void setMonthlyTokenQuota(Long monthlyTokenQuota) {
        this.monthlyTokenQuota = monthlyTokenQuota;
    }

    public Integer getStorageGb() {
        return storageGb;
    }

    public void setStorageGb(Integer storageGb) {
        this.storageGb = storageGb;
    }

    public Integer getIdleTtlSeconds() {
        return idleTtlSeconds;
    }

    public void setIdleTtlSeconds(Integer idleTtlSeconds) {
        this.idleTtlSeconds = idleTtlSeconds;
    }
}
