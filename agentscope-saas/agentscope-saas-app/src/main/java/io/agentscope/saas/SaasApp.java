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
package io.agentscope.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the enterprise SaaS AI assistant platform (Phase 1 foundation).
 *
 * <p>This application assembles the agentscope-java {@code HarnessAgent} runtime with a SaaS
 * middleware chain (tenant context, rate limiting, usage metering) on top of the framework's
 * built-in sandbox lifecycle, permission, and trace middlewares. It exposes:
 *
 * <ul>
 *   <li>JWT/SSO authentication ({@code /api/auth/**})</li>
 *   <li>AG-UI compatible streaming chat ({@code POST /api/agents/{agentId}/chat/stream})</li>
 *   <li>Org-scoped agent and session management ({@code /api/agents/**}, including per-agent sessions)</li>
 * </ul>
 *
 * <p>Run with the {@code local} profile for a zero-dependency smoke test (H2 + in-memory state +
 * stub model), or the default profile to connect PostgreSQL, Valkey/Redis, and a model gateway.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SaasApp {

    public static void main(String[] args) {
        SpringApplication.run(SaasApp.class, args);
    }
}
