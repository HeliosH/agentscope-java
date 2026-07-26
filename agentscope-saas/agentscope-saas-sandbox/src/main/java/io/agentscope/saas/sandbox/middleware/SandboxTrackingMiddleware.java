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
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.sandbox.ActiveSandboxDeployment;
import io.agentscope.saas.sandbox.SandboxBroker;
import io.agentscope.saas.sandbox.SandboxExternalIds;
import io.agentscope.saas.sandbox.SandboxLeaseContext;
import io.agentscope.saas.sandbox.SandboxLeaseService;
import io.agentscope.saas.sandbox.SandboxMetrics;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import io.agentscope.saas.sandbox.SandboxTrackingContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Tracks active sandbox usage in the database so quota enforcement ({@link
 * SandboxQuotaMiddleware}) and system reconciliation operate on real data. The framework owns the
 * sandbox lifecycle; this middleware only records the operational row {@code (org, user, session)
 * -> active} for the duration of an agent run.
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
    private final SandboxMetrics metrics;
    private final SandboxLeaseService leaseService;
    private final ActiveSandboxDeployment deployment;

    public SandboxTrackingMiddleware(
            SandboxBroker broker, String sandboxType, long idleTtlSeconds) {
        this(broker, sandboxType, idleTtlSeconds, SandboxMetrics.noop(), null, null);
    }

    public SandboxTrackingMiddleware(
            SandboxBroker broker, String sandboxType, long idleTtlSeconds, SandboxMetrics metrics) {
        this(broker, sandboxType, idleTtlSeconds, metrics, null, null);
    }

    public SandboxTrackingMiddleware(
            SandboxBroker broker,
            String sandboxType,
            long idleTtlSeconds,
            SandboxMetrics metrics,
            SandboxLeaseService leaseService,
            ActiveSandboxDeployment deployment) {
        this.broker = broker;
        this.sandboxType = sandboxType;
        this.idleTtlSeconds = idleTtlSeconds;
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
        this.leaseService = leaseService;
        this.deployment = deployment;
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
        UUID agentId = parseOptionalUuid(ctx.get(SandboxRuntimeAttributes.ATTR_AGENT_ID));
        String sessionId = ctx.getSessionId();
        String externalId = SandboxExternalIds.fromRuntimeContext(ctx).orElse(sessionId);
        AtomicReference<UUID> trackingId = new AtomicReference<>();
        AtomicReference<SandboxLeaseContext> orchestrationLease = new AtomicReference<>();
        AtomicReference<Disposable> heartbeat = new AtomicReference<>();
        long ttlSeconds = Math.max(1L, idleTtlSeconds);
        long startedAtNanos = System.nanoTime();

        try {
            UUID id =
                    withTenantOrg(
                            tc.orgId(),
                            () ->
                                    broker.registerActive(
                                            orgId,
                                            userId,
                                            agentId,
                                            sessionId,
                                            sandboxType,
                                            externalId,
                                            OffsetDateTime.now().plusSeconds(ttlSeconds),
                                            tc.maxSandboxes()));
            trackingId.set(id);
            ctx.put(SandboxTrackingContext.class, new SandboxTrackingContext(id, tc.orgId()));
        } catch (QuotaExceededException e) {
            metrics.quotaRejected(sandboxType);
            throw e;
        } catch (Exception e) {
            metrics.trackingRegistrationFailed(sandboxType);
            log.warn("Failed to register active sandbox tracking row: {}", e.getMessage());
        }

        try {
            SandboxLeaseContext lease = beginOrchestrationLease(ctx, orgId, userId, ttlSeconds);
            if (lease != null) {
                orchestrationLease.set(lease);
                ctx.put(SandboxLeaseContext.class, lease);
            }
        } catch (RuntimeException e) {
            releaseTrackingRow(trackingId.get(), tc.orgId());
            throw e;
        }

        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException e) {
            releaseOrchestrationLease(orchestrationLease.get(), tc.orgId());
            releaseTrackingRow(trackingId.get(), tc.orgId());
            throw e;
        }

        return downstream
                .doOnSubscribe(
                        subscription ->
                                startHeartbeat(
                                        heartbeat,
                                        trackingId.get(),
                                        orchestrationLease.get(),
                                        tc.orgId(),
                                        ttlSeconds))
                .doFinally(
                        signal -> {
                            metrics.recordRun(
                                    sandboxType, signal.name(), System.nanoTime() - startedAtNanos);
                            Disposable d = heartbeat.getAndSet(null);
                            if (d != null) {
                                d.dispose();
                            }
                            releaseOrchestrationLease(orchestrationLease.get(), tc.orgId());
                            releaseTrackingRow(trackingId.get(), tc.orgId());
                        });
    }

    private SandboxLeaseContext beginOrchestrationLease(
            RuntimeContext ctx, UUID orgId, UUID userId, long ttlSeconds) {
        if (leaseService == null || deployment == null) {
            return null;
        }
        UUID runId = parseOptionalUuid(ctx.get(SandboxRuntimeAttributes.ATTR_RUN_ID));
        if (runId == null) {
            return null;
        }
        UUID taskId = parseOptionalUuid(ctx.get(SandboxRuntimeAttributes.ATTR_TASK_ID));
        UUID attemptId = parseOptionalUuid(ctx.get(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID));
        String leaseOwner = stringValue(ctx.get(SandboxRuntimeAttributes.ATTR_LEASE_OWNER));
        return withTenantOrg(
                orgId.toString(),
                () ->
                        leaseService.begin(
                                orgId,
                                userId,
                                runId,
                                taskId,
                                attemptId,
                                deployment,
                                leaseOwner,
                                OffsetDateTime.now().plusSeconds(ttlSeconds)));
    }

    private void releaseTrackingRow(UUID id, String orgId) {
        if (id == null) {
            return;
        }
        try {
            withTenantOrg(
                    orgId,
                    () -> {
                        broker.release(id);
                        return null;
                    });
        } catch (Exception e) {
            metrics.trackingReleaseFailed(sandboxType);
            log.warn("Failed to release sandbox tracking row {}: {}", id, e.getMessage());
        }
    }

    private void startHeartbeat(
            AtomicReference<Disposable> heartbeat,
            UUID id,
            SandboxLeaseContext orchestrationLease,
            String orgId,
            long ttlSeconds) {
        if (id == null && orchestrationLease == null) {
            return;
        }
        long periodSeconds = Math.max(1L, ttlSeconds / 2L);
        Disposable disposable =
                Schedulers.parallel()
                        .schedulePeriodically(
                                () -> refreshLease(id, orchestrationLease, orgId, ttlSeconds),
                                periodSeconds,
                                periodSeconds,
                                java.util.concurrent.TimeUnit.SECONDS);
        Disposable previous = heartbeat.getAndSet(disposable);
        if (previous != null) {
            previous.dispose();
        }
    }

    private void refreshLease(
            UUID id, SandboxLeaseContext orchestrationLease, String orgId, long ttlSeconds) {
        try {
            withTenantOrg(
                    orgId,
                    () -> {
                        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(ttlSeconds);
                        if (id != null) {
                            broker.refreshLease(id, expiresAt);
                        }
                        if (orchestrationLease != null && leaseService != null) {
                            leaseService.heartbeat(orchestrationLease, expiresAt);
                        }
                        return null;
                    });
        } catch (Exception e) {
            metrics.trackingLeaseRefreshFailed(sandboxType);
            log.warn("Failed to refresh sandbox tracking lease {}: {}", id, e.getMessage());
        }
    }

    private void releaseOrchestrationLease(SandboxLeaseContext lease, String orgId) {
        if (lease == null || leaseService == null) {
            return;
        }
        try {
            withTenantOrg(
                    orgId,
                    () -> {
                        leaseService.release(lease);
                        return null;
                    });
        } catch (Exception e) {
            log.warn(
                    "Failed to release orchestration sandbox lease {}: {}",
                    lease.leaseId(),
                    e.getMessage());
        }
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

    private UUID parseOptionalUuid(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        if (s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid sandbox tracking agent id: {}", s);
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static <T> T withTenantOrg(String orgId, TenantOperation<T> operation) {
        String previous = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(orgId);
        try {
            return operation.run();
        } finally {
            TenantContextHolder.setOrgId(previous);
        }
    }

    @FunctionalInterface
    private interface TenantOperation<T> {
        T run();
    }
}
