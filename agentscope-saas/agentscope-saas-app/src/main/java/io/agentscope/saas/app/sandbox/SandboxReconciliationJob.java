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
package io.agentscope.saas.app.sandbox;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository.OrchestrationLeaseResource;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository.SandboxResource;
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxInventoryMetrics;
import io.agentscope.saas.sandbox.SandboxMetrics;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * System-level reconciliation for sandbox tracking rows and provider-owned backend resources.
 *
 * <p>This job intentionally uses the admin/bypass DataSource because it must scan across tenants.
 * Request paths and org-admin APIs continue to use tenant-scoped repositories.
 */
@Component
public class SandboxReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxReconciliationJob.class);

    private static final int MAX_ERROR_LENGTH = 2000;

    private final SandboxReconciliationRepository repository;
    private final SaasProperties properties;
    private final SandboxBackendTerminator terminator;
    private final SandboxMetrics metrics;
    private final SandboxInventoryMetrics inventoryMetrics;

    @Autowired
    public SandboxReconciliationJob(
            SandboxReconciliationRepository repository,
            SaasProperties properties,
            SandboxBackendTerminator terminator,
            SandboxMetrics metrics,
            SandboxInventoryMetrics inventoryMetrics) {
        this.repository = repository;
        this.properties = properties;
        this.terminator = terminator != null ? terminator : SandboxBackendTerminator.unsupported();
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
        this.inventoryMetrics = inventoryMetrics;
    }

    @Scheduled(
            fixedDelayString = "${saas.sandbox.reconciliation-fixed-delay-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void reconcileScheduled() {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        if (!sandbox.isEnabled() || !sandbox.isReconciliationEnabled()) {
            return;
        }
        try {
            ReconciliationSummary summary = reconcileBatch();
            if (summary.total() > 0) {
                log.info(
                        "Sandbox reconciliation completed expired={} backendReleased={} "
                                + "backendSkipped={} backendFailed={} leaseExpired={} "
                                + "leaseReleased={} leaseFailed={}",
                        summary.expiredActive(),
                        summary.backendReleased(),
                        summary.backendSkipped(),
                        summary.backendFailed(),
                        summary.expiredLeases(),
                        summary.leaseReleased(),
                        summary.leaseFailed());
            }
        } catch (RuntimeException e) {
            log.warn("Sandbox reconciliation scan failed: {}", e.getMessage());
        } finally {
            refreshInventoryMetrics();
        }
    }

    ReconciliationSummary reconcileBatch() {
        SaasProperties.Sandbox sandbox = properties.getSandbox();
        int batchSize = Math.max(1, sandbox.getReconciliationBatchSize());
        int maxAttempts = Math.max(1, sandbox.getBackendReleaseMaxAttempts());
        OffsetDateTime staleBefore =
                OffsetDateTime.now()
                        .minusSeconds(Math.max(0L, sandbox.getReconciliationActiveGraceSeconds()));

        MutableSummary summary = new MutableSummary();
        for (SandboxResource candidate : repository.findExpiredActive(staleBefore, batchSize)) {
            if (repository.markExpiredActiveEvicted(candidate.id(), OffsetDateTime.now()) == 1) {
                metrics.evict(candidate.sandboxType());
                summary.expiredActive++;
                if (repository.claimBackendRelease(candidate.id(), maxAttempts) == 1) {
                    terminateAndRecord(candidate, maxAttempts, summary);
                }
            }
        }

        int remaining = Math.max(0, batchSize - summary.expiredActive);
        if (remaining > 0) {
            for (SandboxResource candidate :
                    repository.findBackendReleaseCandidates(maxAttempts, remaining)) {
                if (repository.claimBackendRelease(candidate.id(), maxAttempts) == 1) {
                    terminateAndRecord(candidate, maxAttempts, summary);
                }
            }
        }

        remaining = Math.max(0, batchSize - summary.expiredActive - summary.expiredLeases);
        Set<UUID> handledLeaseIds = new HashSet<>();
        if (remaining > 0) {
            for (OrchestrationLeaseResource candidate :
                    repository.findExpiredOrchestrationLeases(staleBefore, remaining)) {
                if (repository.markExpiredOrchestrationLease(candidate.id(), OffsetDateTime.now())
                        == 1) {
                    summary.expiredLeases++;
                    handledLeaseIds.add(candidate.id());
                    if (repository.claimOrchestrationLeaseRelease(candidate.id(), maxAttempts)
                            == 1) {
                        terminateLeaseAndRecord(candidate, maxAttempts, summary);
                    }
                }
            }
        }

        remaining = Math.max(0, batchSize - summary.expiredActive - summary.expiredLeases);
        if (remaining > 0) {
            for (OrchestrationLeaseResource candidate :
                    repository.findOrchestrationLeaseReleaseCandidates(maxAttempts, remaining)) {
                if (!handledLeaseIds.contains(candidate.id())
                        && repository.claimOrchestrationLeaseRelease(candidate.id(), maxAttempts)
                                == 1) {
                    terminateLeaseAndRecord(candidate, maxAttempts, summary);
                }
            }
        }
        return summary.toImmutable();
    }

    private void terminateAndRecord(
            SandboxResource candidate, int maxAttempts, MutableSummary summary) {
        SandboxBackendTerminator.TerminationResult result;
        try {
            result = terminator.terminate(candidate.sandboxType(), candidate.externalId());
        } catch (Exception e) {
            result =
                    SandboxBackendTerminator.TerminationResult.failed(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
        recordBackendRelease(candidate.id(), result);
        if (result.attempted() && result.succeeded()) {
            metrics.backendReleaseSucceeded(candidate.sandboxType());
            summary.backendReleased++;
        } else if (result.attempted()) {
            metrics.backendReleaseFailed(candidate.sandboxType());
            summary.backendFailed++;
            log.warn(
                    "Sandbox reconciliation backend release failed id={} type={} externalId={} "
                            + "status={} message={} maxAttempts={}",
                    candidate.id(),
                    candidate.sandboxType(),
                    candidate.externalId(),
                    result.status(),
                    result.message(),
                    maxAttempts);
        } else {
            summary.backendSkipped++;
        }
    }

    private void recordBackendRelease(UUID id, SandboxBackendTerminator.TerminationResult result) {
        int attemptIncrement = result.attempted() ? 1 : 0;
        OffsetDateTime releasedAt = result.succeeded() ? OffsetDateTime.now() : null;
        repository.recordBackendRelease(
                id,
                result.status(),
                attemptIncrement,
                releasedAt,
                result.succeeded() ? null : truncate(result.message()));
    }

    private void terminateLeaseAndRecord(
            OrchestrationLeaseResource candidate, int maxAttempts, MutableSummary summary) {
        if (candidate.providerSandboxId() == null || candidate.providerSandboxId().isBlank()) {
            repository.recordOrchestrationLeaseRelease(
                    candidate.id(), "RELEASED", 0, OffsetDateTime.now(), null);
            summary.leaseReleased++;
            return;
        }
        SandboxBackendTerminator.TerminationResult result;
        try {
            result = terminator.terminate(candidate.providerId(), candidate.providerSandboxId());
        } catch (Exception e) {
            result =
                    SandboxBackendTerminator.TerminationResult.failed(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
        int attemptIncrement = result.attempted() ? 1 : maxAttempts;
        repository.recordOrchestrationLeaseRelease(
                candidate.id(),
                result.succeeded() ? "RELEASED" : "RELEASE_FAILED",
                attemptIncrement,
                result.succeeded() ? OffsetDateTime.now() : null,
                result.succeeded() ? null : truncate(result.message()));
        if (result.succeeded()) {
            metrics.backendReleaseSucceeded(candidate.providerId());
            summary.leaseReleased++;
        } else {
            if (result.attempted()) {
                metrics.backendReleaseFailed(candidate.providerId());
            }
            summary.leaseFailed++;
            log.warn(
                    "Orchestration sandbox lease release failed id={} provider={} sandboxId={} "
                            + "status={} message={} maxAttempts={}",
                    candidate.id(),
                    candidate.providerId(),
                    candidate.providerSandboxId(),
                    result.status(),
                    result.message(),
                    maxAttempts);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private void refreshInventoryMetrics() {
        if (inventoryMetrics == null) {
            return;
        }
        try {
            inventoryMetrics.refresh();
        } catch (RuntimeException e) {
            log.warn("Sandbox inventory metrics refresh failed: {}", e.getMessage());
        }
    }

    record ReconciliationSummary(
            int expiredActive,
            int backendReleased,
            int backendSkipped,
            int backendFailed,
            int expiredLeases,
            int leaseReleased,
            int leaseFailed) {
        int total() {
            return expiredActive
                    + backendReleased
                    + backendSkipped
                    + backendFailed
                    + expiredLeases
                    + leaseReleased
                    + leaseFailed;
        }
    }

    private static final class MutableSummary {
        private int expiredActive;
        private int backendReleased;
        private int backendSkipped;
        private int backendFailed;
        private int expiredLeases;
        private int leaseReleased;
        private int leaseFailed;

        private ReconciliationSummary toImmutable() {
            return new ReconciliationSummary(
                    expiredActive,
                    backendReleased,
                    backendSkipped,
                    backendFailed,
                    expiredLeases,
                    leaseReleased,
                    leaseFailed);
        }
    }
}
