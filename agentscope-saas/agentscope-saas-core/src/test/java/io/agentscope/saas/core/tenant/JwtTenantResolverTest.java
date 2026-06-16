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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.TierPolicyEntity;
import io.agentscope.saas.core.persistence.repo.TierPolicyRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link JwtTenantResolver}: claim parsing and tier-policy enrichment. */
@ExtendWith(MockitoExtension.class)
class JwtTenantResolverTest {

    @Mock private TierPolicyRepository tierPolicyRepository;

    @Test
    void resolvesClaimsAndEnrichesQuotaFromTierPolicy() {
        TierPolicyEntity policy = new TierPolicyEntity();
        policy.setTier("advanced");
        policy.setMaxSandboxes(3);
        policy.setMonthlyTokenQuota(10_000_000L);
        when(tierPolicyRepository.findById("advanced")).thenReturn(Optional.of(policy));

        JwtTenantResolver resolver = new JwtTenantResolver(tierPolicyRepository);
        TenantContext tc =
                resolver.resolve(
                        Map.of(
                                "org_id", "org-1",
                                "user_id", "user-1",
                                "role", "admin",
                                "tier", "advanced"));

        assertEquals("org-1", tc.orgId());
        assertEquals("user-1", tc.userId());
        assertEquals("admin", tc.role());
        assertEquals("advanced", tc.tier());
        assertEquals(3, tc.maxSandboxes());
        assertEquals(10_000_000L, tc.tokenQuota());
    }

    @Test
    void fallsBackToSubjectWhenUserIdAbsent() {
        when(tierPolicyRepository.findById("standard")).thenReturn(Optional.empty());

        JwtTenantResolver resolver = new JwtTenantResolver(tierPolicyRepository);
        TenantContext tc = resolver.resolve(Map.of("org_id", "org-9", "sub", "subject-9"));

        assertEquals("org-9", tc.orgId());
        assertEquals("subject-9", tc.userId());
        // No policy row -> safe defaults.
        assertEquals(1, tc.maxSandboxes());
        assertEquals(0L, tc.tokenQuota());
        assertEquals("standard", tc.tier());
    }
}
