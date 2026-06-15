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
package io.agentscope.saas.core.tenant;

import io.agentscope.saas.app.persistence.entity.TierPolicyEntity;
import io.agentscope.saas.app.persistence.repo.TierPolicyRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Default {@link TenantResolver} that reads {@code org_id}, {@code user_id}, {@code role}, and
 * {@code tier} from JWT claims and enriches the context with quota limits from the
 * {@code tier_policies} table (tier-based quotas, not billing subscriptions).
 */
@Component
public class JwtTenantResolver implements TenantResolver {

    private static final String DEFAULT_TIER = "standard";

    private final TierPolicyRepository tierPolicyRepository;

    public JwtTenantResolver(TierPolicyRepository tierPolicyRepository) {
        this.tierPolicyRepository = tierPolicyRepository;
    }

    @Override
    public TenantContext resolve(Map<String, Object> claims) {
        String orgId = asString(claims.get("org_id"));
        String userId = asString(claims.get("user_id"));
        if (userId == null) {
            // Fall back to the standard JWT subject claim.
            userId = asString(claims.get("sub"));
        }
        String role = asString(claims.getOrDefault("role", "member"));
        String tier = asString(claims.getOrDefault("tier", DEFAULT_TIER));
        if (tier == null) {
            tier = DEFAULT_TIER;
        }

        int maxSandboxes = 1;
        long tokenQuota = 0L;
        TierPolicyEntity policy = tierPolicyRepository.findById(tier).orElse(null);
        if (policy != null) {
            maxSandboxes = policy.getMaxSandboxes() != null ? policy.getMaxSandboxes() : 1;
            tokenQuota = policy.getMonthlyTokenQuota() != null ? policy.getMonthlyTokenQuota() : 0L;
        }
        return new TenantContext(orgId, userId, role, tier, maxSandboxes, tokenQuota);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
