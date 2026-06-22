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
package io.agentscope.saas.sandbox.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.sandbox.SandboxBroker;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Tracks active sandbox usage in the database so that quota enforcement
 * ({@link SandboxQuotaMiddleware}) and idle eviction ({@code SandboxEvictionJob}) operate on real
 * data. The framework owns the sandbox lifecycle; this middleware only records the operational
 * row {@code (org, user, session) -> active} for the duration of an agent run.
 *
 * <p>Ordering: this runs after {@link SandboxQuotaMiddleware} (which gates on the count) and around
 * the framework's sandbox-lifecycle middleware, so a row exists while the sandbox is in use and is
 * marked released when the run completes.
 */
public class SandboxTrackingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SandboxTrackingMiddleware.class);

    private final SandboxBroker broker;
    private final String sandboxType;
    private final long idleTtlSeconds;

    public SandboxTrackingMiddleware(
            SandboxBroker broker, String sandboxType, long idleTtlSeconds) {
        this.broker = broker;
        this.sandboxType = sandboxType;
        this.idleTtlSeconds = idleTtlSeconds;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        TenantContext tc = TenantContext.from(ctx);
        if (tc == null || tc.orgId() == null || tc.userId() == null) {
            return next.apply(input);
        }

        // Dev/bypass tenants use non-UUID ids (e.g. "dev-org"); the sandboxes tracking table keys
        // on
        // UUID org/user ids, so skip tracking entirely for them — same convention as
        // SaasChatController.isPersistable skipping message persistence for non-UUID tenants.
        if (!isUuid(tc.orgId()) || !isUuid(tc.userId())) {
            return next.apply(input);
        }

        UUID orgId = UUID.fromString(tc.orgId());
        UUID userId = UUID.fromString(tc.userId());
        String sessionId = ctx.getSessionId();
        AtomicReference<UUID> trackingId = new AtomicReference<>();

        try {
            // External id is unknown at this layer (the framework owns the backend handle); the
            // sessionId identifies the sandbox slot under USER/SESSION isolation.
            UUID id =
                    broker.registerActive(
                            orgId,
                            userId,
                            sessionId,
                            sandboxType,
                            sessionId,
                            OffsetDateTime.now().plusSeconds(idleTtlSeconds));
            trackingId.set(id);
        } catch (Exception e) {
            log.warn("Failed to register active sandbox tracking row: {}", e.getMessage());
        }

        return next.apply(input)
                .doFinally(
                        signal -> {
                            UUID id = trackingId.get();
                            if (id != null) {
                                try {
                                    broker.release(id);
                                } catch (Exception e) {
                                    log.warn(
                                            "Failed to release sandbox tracking row {}: {}",
                                            id,
                                            e.getMessage());
                                }
                            }
                        });
    }

    private static boolean isUuid(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
