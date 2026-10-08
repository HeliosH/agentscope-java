/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.SessionFence;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Authenticated epoch reads; final publication still locks/revalidates inside its SQL transaction. */
@Service
public class SessionRunFenceService {
    public record Binding(UUID runId, UUID orgId, UUID userId, UUID agentId, SessionFence fence) {}

    private final RunOrchestrationRepository repository;

    public SessionRunFenceService(RunOrchestrationRepository repository) {
        this.repository = repository;
    }

    public Binding bind(RuntimeContext context) {
        TenantContext tenant = TenantContext.from(context);
        if (tenant == null) return null;
        if (!tenant.userId().equals(context.getUserId()))
            throw new SessionExecutionRevokedException();
        UUID run = uuid(context.get(RunOrchestrationService.ATTR_RUN_ID));
        UUID agent = uuid(context.get(SandboxRuntimeAttributes.ATTR_AGENT_ID));
        if (agent == null) {
            if (run != null) throw new SessionExecutionRevokedException();
            return null;
        }
        UUID org = UUID.fromString(tenant.orgId()), user = UUID.fromString(tenant.userId());
        return owned(
                org,
                () -> {
                    if (run != null) {
                        var fence =
                                repository
                                        .findCurrentSessionFence(run, org, user, agent)
                                        .orElseThrow(SessionExecutionRevokedException::new);
                        return new Binding(run, org, user, agent, fence);
                    }
                    Binding inherited = context.get(Binding.class);
                    if (inherited != null) {
                        if (!org.equals(inherited.orgId())
                                || !user.equals(inherited.userId())
                                || !agent.equals(inherited.agentId())
                                || !current(inherited))
                            throw new SessionExecutionRevokedException();
                        return inherited;
                    }
                    UUID session = uuid(context.getSessionId());
                    return session == null
                            ? null
                            : repository
                                    .findSessionFence(session, org, user, agent)
                                    .map(fence -> new Binding(null, org, user, agent, fence))
                                    .orElse(null);
                });
    }

    public boolean current(Binding binding) {
        return owned(
                binding.orgId(),
                () ->
                        (binding.runId() == null
                                        ? repository.findSessionFence(
                                                binding.fence().sessionId(),
                                                binding.orgId(),
                                                binding.userId(),
                                                binding.agentId())
                                        : repository.findCurrentSessionFence(
                                                binding.runId(),
                                                binding.orgId(),
                                                binding.userId(),
                                                binding.agentId()))
                                .filter(binding.fence()::equals)
                                .isPresent());
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new SessionExecutionRevokedException();
        }
    }

    private static <T> T owned(UUID org, Supplier<T> action) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(org.toString());
        try {
            return action.get();
        } finally {
            TenantContextHolder.setOrgId(prior);
        }
    }
}
