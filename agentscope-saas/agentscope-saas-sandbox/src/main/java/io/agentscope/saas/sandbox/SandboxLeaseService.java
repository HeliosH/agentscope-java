/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.NewSandboxLease;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.SandboxLease;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service that owns orchestration sandbox lease state transitions. */
@Service
public class SandboxLeaseService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final SandboxLeaseRepository repository;

    public SandboxLeaseService(SandboxLeaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SandboxLeaseContext begin(
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            ActiveSandboxDeployment deployment,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID leaseId = UUID.randomUUID();
        int inserted =
                repository.insert(
                        new NewSandboxLease(
                                leaseId,
                                orgId,
                                userId,
                                runId,
                                taskId,
                                attemptId,
                                deployment.providerId(),
                                deployment.imageOrTemplate(),
                                deployment.capabilitiesJson(),
                                normalize(leaseOwner),
                                leaseExpiresAt,
                                now));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Sandbox lease " + leaseId + " insert affected " + inserted + " rows");
        }
        return new SandboxLeaseContext(leaseId, orgId);
    }

    @Transactional
    public boolean activate(
            SandboxLeaseContext context,
            String providerSandboxId,
            String providerStateJson,
            OffsetDateTime leaseExpiresAt) {
        if (context == null) {
            return false;
        }
        return repository.activate(
                        context.leaseId(),
                        context.orgId(),
                        normalize(providerSandboxId),
                        validJson(providerStateJson),
                        OffsetDateTime.now(),
                        leaseExpiresAt)
                == 1;
    }

    @Transactional
    public boolean heartbeat(SandboxLeaseContext context, OffsetDateTime leaseExpiresAt) {
        if (context == null) {
            return false;
        }
        return repository.heartbeat(
                        context.leaseId(), context.orgId(), OffsetDateTime.now(), leaseExpiresAt)
                == 1;
    }

    @Transactional
    public boolean checkpoint(
            SandboxLeaseContext context, String workspaceSnapshotUri, String workspaceVersion) {
        if (context == null) {
            return false;
        }
        return repository.checkpoint(
                        context.leaseId(),
                        context.orgId(),
                        normalize(workspaceSnapshotUri),
                        normalize(workspaceVersion))
                == 1;
    }

    @Transactional
    public boolean checkpointForAttempt(
            SandboxLeaseContext context,
            UUID orgId,
            UUID userId,
            UUID runId,
            UUID taskId,
            UUID attemptId,
            String workspaceSnapshotUri,
            String workspaceVersion) {
        if (context != null && !orgId.equals(context.orgId()))
            throw new IllegalStateException("SANDBOX_CHECKPOINT_SCOPE_MISMATCH");
        // Harness-derived contexts do not export newly added lease bindings back to their caller.
        var found =
                context == null
                        ? repository.findByAttemptId(attemptId, orgId)
                        : repository.findById(context.leaseId(), orgId);
        if (found.isEmpty()) return context == null;
        var lease = found.orElseThrow();
        if (!orgId.equals(lease.orgId())
                || !userId.equals(lease.userId())
                || !runId.equals(lease.runId())
                || !taskId.equals(lease.taskId())
                || !attemptId.equals(lease.attemptId()))
            throw new IllegalStateException("SANDBOX_CHECKPOINT_SCOPE_MISMATCH");
        return repository.checkpoint(
                        lease.id(),
                        orgId,
                        normalize(workspaceSnapshotUri),
                        normalize(workspaceVersion))
                == 1;
    }

    @Transactional
    public boolean release(SandboxLeaseContext context) {
        if (context == null) {
            return false;
        }
        return repository.release(context.leaseId(), context.orgId(), OffsetDateTime.now()) == 1;
    }

    @Transactional
    public boolean provisioningFailed(SandboxLeaseContext context, Throwable error) {
        if (context == null) {
            return false;
        }
        return repository.releaseAfterProvisioningFailure(
                        context.leaseId(),
                        context.orgId(),
                        OffsetDateTime.now(),
                        sanitizeError(error))
                == 1;
    }

    @Transactional(readOnly = true)
    public Optional<SandboxLease> findByAttemptId(UUID orgId, UUID attemptId) {
        return repository.findByAttemptId(attemptId, orgId);
    }

    private static String validJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sanitizeError(Throwable error) {
        String message =
                error == null || error.getMessage() == null || error.getMessage().isBlank()
                        ? "Sandbox provisioning failed"
                        : error.getMessage().trim().replaceAll("\\s+", " ");
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
