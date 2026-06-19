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

import io.agentscope.core.agent.RuntimeContext;

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

    /**
     * RuntimeContext attribute key under which this context is stored as a string extra. The harness
     * rebuilds the {@link RuntimeContext} in {@code ensureSessionDefaults} via {@code putAll(ctx.getExtra())},
     * which copies string extras but NOT typed singletons — so this string key is the only storage
     * that survives that rebuild. Middlewares must therefore read via {@link #from(RuntimeContext)}
     * (string key), not {@code ctx.get(TenantContext.class)} (typed), which returns {@code null} on
     * the rebuilt context.
     */
    public static final String ATTR_KEY = "tenantContext";

    /**
     * Reads this context from a {@link RuntimeContext}, preferring the typed singleton and falling
     * back to the {@link #ATTR_KEY} string extra (the only slot that survives the harness's context
     * rebuild). Returns {@code null} if absent.
     */
    public static TenantContext from(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        TenantContext typed = ctx.get(TenantContext.class);
        if (typed != null) {
            return typed;
        }
        Object v = ctx.getExtra().get(ATTR_KEY);
        return v instanceof TenantContext tc ? tc : null;
    }
}
