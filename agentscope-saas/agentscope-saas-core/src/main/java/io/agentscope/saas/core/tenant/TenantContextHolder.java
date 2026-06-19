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
 * <p>Bridged across Reactor's {@code boundedElastic} scheduler boundary by {@code
 * TenantContextWebFilter} + a {@code Schedulers.onScheduleHook}: the filter sets the holder on the
 * Netty request thread, and the schedule hook copies it onto the worker thread that runs each
 * {@code Mono.fromCallable} repository call.
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
