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

import io.agentscope.saas.core.tenant.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link TenantContextHolder} across Reactor scheduler boundaries. {@code
 * TenantRlsWebFilter} sets the holder on the Netty request thread; repository calls run on {@code
 * boundedElastic} via {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}. This hook wraps
 * every scheduled task so the org id is copied onto the worker thread before it runs and cleared
 * after, keeping RLS scoping correct without per-callpoint plumbing.
 *
 * <p>Registered once via {@code Schedulers.onScheduleHook} on startup. The hook captures the
 * <em>submitting</em> thread's org id at schedule time and re-applies it on the worker.
 */
@Component
public class TenantRlsSchedulerHook {

    private static final String HOOK_KEY = "tenant-rls-bridge";

    @PostConstruct
    public void install() {
        reactor.core.scheduler.Schedulers.onScheduleHook(
                HOOK_KEY,
                runnable -> {
                    String captured = TenantContextHolder.getOrgId();
                    return () -> {
                        String previous = TenantContextHolder.getOrgId();
                        TenantContextHolder.setOrgId(captured);
                        try {
                            runnable.run();
                        } finally {
                            // Restore the worker's prior state (usually empty) to avoid leakage.
                            TenantContextHolder.setOrgId(previous);
                        }
                    };
                });
    }
}
