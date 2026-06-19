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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * The Spring {@code @Async} counterpart of {@link TenantContextPropagator}. {@code
 * TenantContextPropagator} bridges {@link TenantContextHolder} across Reactor scheduler boundaries
 * via the Reactor Context, but Spring's async {@code TaskExecutor} is a separate scheduler that the
 * Reactor Context does not flow into. Without this decorator, an {@code @Async} method (e.g. {@code
 * UsageService.record}) runs on an async worker with no tenant in the holder, so the RLS-wrapped
 * DataSource sets an empty {@code app.current_org} and every tenant-table write is denied by
 * Row-Level Security — silently swallowed by the method's own error handling.
 *
 * <p>The decorator captures the submitting thread's org id when the task is handed to the executor
 * and re-applies it on the worker thread before the task runs, clearing afterward to avoid
 * thread-pool leakage. Spring Boot auto-applies a {@link TaskDecorator} bean to the auto-configured
 * async executor, so this one bean covers all {@code @Async} methods.
 */
@Configuration
public class TenantAsyncConfig {

    @Bean
    public TaskDecorator tenantAwareTaskDecorator() {
        return runnable -> {
            String captured = TenantContextHolder.getOrgId();
            return () -> {
                String previous = TenantContextHolder.getOrgId();
                TenantContextHolder.setOrgId(captured);
                try {
                    runnable.run();
                } finally {
                    TenantContextHolder.setOrgId(previous);
                }
            };
        };
    }
}
