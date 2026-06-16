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

/**
 * Immutable per-request tenant context, derived from the authenticated principal (JWT claims) and
 * the user's quota tier policy. Injected into the agent {@code RuntimeContext} by
 * {@link io.agentscope.saas.core.middleware.TenantContextMiddleware} so that downstream middlewares,
 * tools, and the sandbox/filesystem isolation layer can scope all work to {@code (orgId, userId)}.
 *
 * @param orgId organization (tenant) identifier
 * @param userId user identifier
 * @param role coarse RBAC role (owner/admin/member/viewer)
 * @param tier quota tier (standard/advanced/privileged)
 * @param maxSandboxes maximum concurrent sandboxes allowed by the tier
 * @param tokenQuota monthly token quota allowed by the tier
 */
public record TenantContext(
        String orgId, String userId, String role, String tier, int maxSandboxes, long tokenQuota) {

    /** RuntimeContext attribute key used when storing this context as a string extra (debugging). */
    public static final String ATTR_KEY = "tenantContext";
}
