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
 * Thread-local holder for the current request's org id, used to propagate the tenant into the JDBC
 * layer so {@code TenantAwareDataSource} can issue {@code SET LOCAL app.current_org} for Row-Level
 * Security.
 *
 * <p>Bridged across two scheduler boundaries: (1) Reactor's various schedulers (Netty request
 * thread, {@code parallel-N}, {@code boundedElastic}) by a {@code Hooks.onEachOperator} hook (see
 * {@code TenantContextPropagator}) that re-applies the org id from the Reactor {@code Context}
 * (written by {@code TenantRlsWebFilter}) onto whichever thread runs each operator — so repository
 * {@code Mono.fromCallable} calls on {@code boundedElastic} see the GUC set correctly; (2) Spring's
 * async {@code TaskExecutor} by a {@code TaskDecorator} (see {@code TenantAsyncConfig}) — so {@code @Async} methods that write tenant tables (e.g. usage
 * metering) also run with the GUC set and are not denied by Row-Level Security.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<String> ORG_ID = new ThreadLocal<>();

    private TenantContextHolder() {}

    /** Sets the current org id (or clears it when {@code null}). */
    public static void setOrgId(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            ORG_ID.remove();
        } else {
            ORG_ID.set(orgId);
        }
    }

    /** Returns the current org id, or {@code null} if none set (anonymous/system context). */
    public static String getOrgId() {
        return ORG_ID.get();
    }

    /** Clears the holder. Call in {@code finally} to avoid thread-pool leakage. */
    public static void clear() {
        ORG_ID.remove();
    }
}
