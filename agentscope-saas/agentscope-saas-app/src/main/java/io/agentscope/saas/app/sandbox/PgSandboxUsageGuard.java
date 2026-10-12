/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxUsageGuard;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Reference;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Resource;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository.Scope;
import io.agentscope.saas.sandbox.SandboxExternalIds;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent call-use admission. Heartbeats never revive an expired reference or a closing resource. */
@Service
public class PgSandboxUsageGuard implements SandboxUsageGuard {
    private record Identity(
            UUID org,
            UUID user,
            UUID agent,
            UUID run,
            UUID task,
            UUID attempt,
            String leaseOwner,
            int quota) {}

    private final SandboxUsageRepository repository;
    private final RunOrchestrationRepository runs;
    private final UserRepository users;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;

    public PgSandboxUsageGuard(
            SandboxUsageRepository repository,
            RunOrchestrationRepository runs,
            UserRepository users,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.repository = repository;
        this.runs = runs;
        this.users = users;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(5);
    }

    public String deploymentKey() {
        var p = properties.getSandbox();
        String endpoint =
                switch (p.getType()) {
                    case "opensandbox" -> p.getOpenSandboxApiBaseUrl();
                    case "cube" -> p.getCubeApiUrl();
                    case "e2b" -> p.getE2bApiBaseUrl();
                    case "docker" ->
                            Objects.toString(System.getenv("DOCKER_HOST"), "local")
                                    + ":"
                                    + Objects.toString(System.getenv("DOCKER_CONTEXT"), "default");
                    default -> "local";
                };
        // Public endpoint and configured deployment identity, never model/provider credentials.
        return StepSnapshot.fingerprint(
                Map.of(
                        "provider",
                        p.getType(),
                        "endpoint",
                        Objects.toString(endpoint, ""),
                        "deployment",
                        properties.getOrchestration().getDeploymentScope()));
    }

    private long ttl() {
        return Math.max(30, properties.getSandbox().getIdleTtlSeconds());
    }

    public long heartbeatSeconds() {
        return Math.max(1, ttl() / 3);
    }

    @Override
    public Binding reserve(RuntimeContext ctx, SandboxIsolationKey slot) {
        Identity identity = identity(ctx);
        if (identity == null) return null;
        if (slot == null) throw denied();
        String key = slot.getScope().name() + ":" + slot.getValue();
        if (key.length() > 1024) throw denied();
        return owned(
                identity,
                () ->
                        transactions.execute(
                                tx -> {
                                    users.lockTenantUser(identity.org(), identity.user())
                                            .orElseThrow(PgSandboxUsageGuard::denied);
                                    var run =
                                            runs.findOwnedRun(
                                                            identity.run(),
                                                            identity.org(),
                                                            identity.user(),
                                                            identity.agent())
                                                    .orElseThrow(PgSandboxUsageGuard::denied);
                                    var fence =
                                            runs.findCurrentSessionFence(
                                                            identity.run(),
                                                            identity.org(),
                                                            identity.user(),
                                                            identity.agent())
                                                    .orElseThrow(PgSandboxUsageGuard::denied);
                                    Scope scope =
                                            scope(identity, run.sessionId(), fence.generation());
                                    require(repository.authority(scope));
                                    if (repository.slotOccupied(
                                            identity.org(),
                                            identity.user(),
                                            properties.getSandbox().getType(),
                                            deploymentKey(),
                                            key))
                                        throw new IllegalStateException(
                                                "SANDBOX_RESOURCE_SLOT_IN_USE");
                                    if (repository.countOccupied(identity.org(), identity.user())
                                            >= identity.quota())
                                        throw new QuotaExceededException(
                                                "Sandbox resource quota exceeded");
                                    UUID resource = UUID.randomUUID(), ref = UUID.randomUUID();
                                    var now = OffsetDateTime.now();
                                    repository.insert(
                                            new Resource(
                                                    resource,
                                                    identity.org(),
                                                    identity.user(),
                                                    identity.agent(),
                                                    identity.run(),
                                                    run.sessionId(),
                                                    fence.generation(),
                                                    properties.getSandbox().getType(),
                                                    deploymentKey(),
                                                    key,
                                                    null,
                                                    "PROVISIONING",
                                                    null,
                                                    null,
                                                    0,
                                                    now));
                                    repository.insert(
                                            new Reference(
                                                    ref,
                                                    resource,
                                                    identity.org(),
                                                    identity.user(),
                                                    identity.task(),
                                                    identity.attempt(),
                                                    identity.leaseOwner(),
                                                    null,
                                                    "ACTIVE",
                                                    now.plusSeconds(ttl())));
                                    return new Binding(resource.toString(), ref.toString(), false);
                                }));
    }

    @Override
    public void activate(RuntimeContext ctx, Binding binding, Sandbox sandbox) {
        if (binding == null) return;
        boolean current =
                mutate(
                        ctx,
                        binding,
                        (i, r, ref) -> {
                            require(!binding.borrowed() && ref.parentId() == null);
                            String external =
                                    SandboxExternalIds.fromSandbox(sandbox)
                                            .orElseThrow(PgSandboxUsageGuard::denied);
                            require(external.length() <= 512);
                            // Commit the allocation receipt even if cancellation/expiry revoked
                            // use.
                            // A failed cleanup must remain discoverable by the resource reaper.
                            if (r.externalId() == null) require(repository.activate(r, external));
                            else require(external.equals(r.externalId()));
                            return List.of("PROVISIONING", "ACTIVE").contains(r.status())
                                    && "ACTIVE".equals(ref.status())
                                    && ref.expiresAt().isAfter(OffsetDateTime.now())
                                    && repository.authority(
                                            scope(i, r.sessionId(), r.sessionGeneration()));
                        });
        require(current);
    }

    @Override
    public Binding borrow(RuntimeContext ctx, Binding parent, Sandbox sandbox) {
        Identity i = identity(ctx);
        if (i == null) return null;
        if (parent == null) throw denied();
        return mutate(
                ctx,
                parent,
                (identity, r, ref) -> {
                    // The parent identity belongs to its own task. Validate the child's execution
                    // separately.
                    require(
                            "ACTIVE".equals(r.status())
                                    && "ACTIVE".equals(ref.status())
                                    && ref.expiresAt().isAfter(OffsetDateTime.now()));
                    require(
                            repository.authority(
                                    new Scope(
                                            r.orgId(),
                                            r.userId(),
                                            r.agentId(),
                                            r.runId(),
                                            ref.taskId(),
                                            ref.attemptId(),
                                            ref.leaseOwner(),
                                            r.sessionId(),
                                            r.sessionGeneration())));
                    require(
                            repository.authority(
                                    scope(identity, r.sessionId(), r.sessionGeneration())));
                    require(
                            Objects.equals(
                                    r.externalId(),
                                    SandboxExternalIds.fromSandbox(sandbox).orElse(null)));
                    UUID id = UUID.randomUUID();
                    repository.insert(
                            new Reference(
                                    id,
                                    r.id(),
                                    identity.org(),
                                    identity.user(),
                                    identity.task(),
                                    identity.attempt(),
                                    identity.leaseOwner(),
                                    ref.id(),
                                    "ACTIVE",
                                    OffsetDateTime.now().plusSeconds(ttl())));
                    return new Binding(r.id().toString(), id.toString(), true);
                },
                true);
    }

    @Override
    public void requireCurrent(RuntimeContext ctx, Binding binding, Sandbox sandbox) {
        if (binding == null) return;
        mutate(
                ctx,
                binding,
                (i, r, ref) -> {
                    requireCurrent(i, r, ref);
                    require(
                            Objects.equals(
                                    r.externalId(),
                                    SandboxExternalIds.fromSandbox(sandbox).orElse(null)));
                    return null;
                });
    }

    @Override
    public boolean heartbeat(RuntimeContext ctx, Binding binding) {
        if (binding == null) return true;
        return mutate(
                ctx,
                binding,
                (i, r, ref) -> {
                    requireCurrent(i, r, ref);
                    return repository.heartbeat(ref, OffsetDateTime.now().plusSeconds(ttl()));
                });
    }

    @Override
    public void prepareCompletion(RuntimeContext ctx, Binding binding) {
        if (binding == null || binding.borrowed()) return;
        mutate(
                ctx,
                binding,
                (i, r, ref) -> {
                    requireCurrent(i, r, ref);
                    if (repository.activeReferences(r.id(), ref.id()) != 0)
                        throw new IllegalStateException("SANDBOX_RESOURCE_USERS_PENDING");
                    if ("ACTIVE".equals(r.status()))
                        require(repository.state(r, "FINALIZING", null, null));
                    else require("FINALIZING".equals(r.status()));
                    return null;
                });
    }

    @Override
    public boolean close(RuntimeContext ctx, Binding binding, boolean retained) {
        if (binding == null) return true;
        return mutate(
                ctx,
                binding,
                (i, r, ref) -> {
                    if (!repository.closeReference(ref)) return false;
                    if (binding.borrowed()) return false;
                    if (List.of("RELEASING", "RELEASED", "RELEASE_FAILED").contains(r.status()))
                        return false;
                    if (retained) {
                        require(repository.state(r, "RETAINED", null, null));
                        return false;
                    }
                    if (repository.activeReferences(r.id(), ref.id()) != 0) {
                        require(repository.state(r, "DRAINING", null, null));
                        return false;
                    }
                    if (r.externalId() == null) {
                        require(repository.state(r, "RELEASED", null, null));
                        return true;
                    }
                    require(
                            repository.state(
                                    r,
                                    "RELEASING",
                                    ref.id(),
                                    OffsetDateTime.now().plusSeconds(60)));
                    return true;
                });
    }

    @Override
    public void released(RuntimeContext ctx, Binding binding, boolean succeeded) {
        if (binding == null) return;
        Identity i = Objects.requireNonNull(identity(ctx));
        owned(
                i,
                () ->
                        transactions.execute(
                                tx ->
                                        repository.finish(
                                                i.org(),
                                                UUID.fromString(binding.resourceId()),
                                                UUID.fromString(binding.referenceId()),
                                                succeeded)));
    }

    private void requireCurrent(Identity i, Resource r, Reference ref) {
        require(
                List.of("PROVISIONING", "ACTIVE", "FINALIZING", "DRAINING").contains(r.status())
                        && "ACTIVE".equals(ref.status())
                        && ref.expiresAt().isAfter(OffsetDateTime.now())
                        && repository.authority(scope(i, r.sessionId(), r.sessionGeneration())));
    }

    private <T> T mutate(RuntimeContext ctx, Binding b, Action<T> action) {
        return mutate(ctx, b, action, false);
    }

    private <T> T mutate(RuntimeContext ctx, Binding b, Action<T> action, boolean parent) {
        Identity i = Objects.requireNonNull(identity(ctx));
        return owned(
                i,
                () ->
                        transactions.execute(
                                tx -> {
                                    Resource r =
                                            repository
                                                    .lock(
                                                            i.org(),
                                                            i.user(),
                                                            UUID.fromString(b.resourceId()))
                                                    .orElseThrow(PgSandboxUsageGuard::denied);
                                    Reference ref =
                                            repository
                                                    .reference(
                                                            i.org(),
                                                            i.user(),
                                                            UUID.fromString(b.referenceId()))
                                                    .orElseThrow(PgSandboxUsageGuard::denied);
                                    require(
                                            r.runId().equals(i.run())
                                                    && r.agentId().equals(i.agent())
                                                    && r.deploymentKey().equals(deploymentKey())
                                                    && r.id().equals(ref.resourceId())
                                                    && (parent
                                                            || (ref.taskId().equals(i.task())
                                                                    && ref.attemptId()
                                                                            .equals(i.attempt())
                                                                    && Objects.equals(
                                                                            ref.leaseOwner(),
                                                                            i.leaseOwner()))));
                                    require(b.borrowed() == (ref.parentId() != null));
                                    return action.apply(i, r, ref);
                                }));
    }

    private static Scope scope(Identity i, UUID session, long generation) {
        return new Scope(
                i.org(),
                i.user(),
                i.agent(),
                i.run(),
                i.task(),
                i.attempt(),
                i.leaseOwner(),
                session,
                generation);
    }

    private static Identity identity(RuntimeContext ctx) {
        TenantContext tenant = TenantContext.from(ctx);
        String run = ctx == null ? null : ctx.get(SandboxRuntimeAttributes.ATTR_RUN_ID);
        if (tenant == null || run == null) return null;
        require(Objects.equals(tenant.userId(), ctx.getUserId()));
        try {
            return new Identity(
                    UUID.fromString(tenant.orgId()),
                    UUID.fromString(tenant.userId()),
                    UUID.fromString(ctx.get(SandboxRuntimeAttributes.ATTR_AGENT_ID)),
                    UUID.fromString(run),
                    UUID.fromString(ctx.get(SandboxRuntimeAttributes.ATTR_TASK_ID)),
                    UUID.fromString(ctx.get(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID)),
                    ctx.get(SandboxRuntimeAttributes.ATTR_LEASE_OWNER),
                    tenant.maxSandboxes());
        } catch (RuntimeException invalid) {
            throw denied();
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw denied();
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("SANDBOX_USAGE_REVOKED_OR_SCOPE_MISMATCH");
    }

    private static <T> T owned(Identity i, Supplier<T> action) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(i.org().toString());
        try {
            return action.get();
        } finally {
            TenantContextHolder.setOrgId(prior);
        }
    }

    private interface Action<T> {
        T apply(Identity i, Resource r, Reference ref);
    }
}
