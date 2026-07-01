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
package io.agentscope.saas.sandbox;

import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.agentscope.saas.core.persistence.repo.UserRepository;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SaaS-level sandbox quota manager. Does NOT create sandboxes (the framework's
 * {@code SandboxManager} handles lifecycle via {@code SandboxLifecycleMiddleware}). Instead, this
 * service enforces per-tenant sandbox limits and tracks active sandbox instances in the database
 * for operational visibility.
 *
 * <p>Typical flow:
 * <ol>
 *   <li>{@link #checkQuota} — called by {@code SandboxQuotaMiddleware} before framework acquire</li>
 *   <li>{@link #registerActive} — called after framework successfully acquires a sandbox</li>
 *   <li>{@link #release} — called after framework releases a sandbox</li>
 * </ol>
 */
@Service
public class SandboxBroker {

    private static final Logger log = LoggerFactory.getLogger(SandboxBroker.class);

    private final SandboxRepository sandboxRepository;
    private final UserRepository userRepository;
    private final SandboxMetrics metrics;

    public record ForceEvictResult(SandboxEntity sandbox, String previousStatus, boolean changed) {}

    public SandboxBroker(SandboxRepository sandboxRepository, UserRepository userRepository) {
        this(sandboxRepository, userRepository, SandboxMetrics.noop());
    }

    @Autowired
    public SandboxBroker(
            SandboxRepository sandboxRepository,
            UserRepository userRepository,
            SandboxMetrics metrics) {
        this.sandboxRepository = sandboxRepository;
        this.userRepository = userRepository;
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
    }

    /**
     * Checks whether the user is within their concurrent sandbox quota. Throws
     * {@link QuotaExceededException} if the limit is reached.
     *
     * @param orgId        organization ID
     * @param userId       user ID
     * @param maxSandboxes maximum concurrent sandboxes allowed by the tier policy
     */
    public void checkQuota(UUID orgId, UUID userId, int maxSandboxes) {
        int active = sandboxRepository.countByOrgIdAndUserIdAndStatus(orgId, userId, "active");
        if (active >= maxSandboxes) {
            throw new QuotaExceededException(
                    "Sandbox quota exceeded for user "
                            + userId
                            + " in org "
                            + orgId
                            + " ("
                            + active
                            + "/"
                            + maxSandboxes
                            + ")");
        }
    }

    /**
     * Records a newly active sandbox after the framework has successfully acquired one.
     *
     * @param orgId       organization ID
     * @param userId      user ID
     * @param sessionId   the session that triggered the sandbox
     * @param sandboxType backend type (docker, cube, etc.)
     * @param externalId  container ID / sandbox ID from the backend
     * @param expiresAt   when the sandbox becomes eligible for eviction
     * @param maxSandboxes maximum concurrent sandboxes allowed by the tier policy
     * @return the database ID of the newly created tracking record
     */
    @Transactional
    public UUID registerActive(
            UUID orgId,
            UUID userId,
            String sessionId,
            String sandboxType,
            String externalId,
            OffsetDateTime expiresAt,
            int maxSandboxes) {

        userRepository
                .lockTenantUser(orgId, userId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Cannot register sandbox for unknown tenant user "
                                                + userId
                                                + " in org "
                                                + orgId));
        checkQuota(orgId, userId, maxSandboxes);

        SandboxEntity entity = new SandboxEntity();
        // SandboxEntity id has no @GeneratedValue (saas convention: assign in the service layer, as
        // ChatPersistenceService does) — without this the persist throws "Identifier must be
        // manually assigned before calling persist()", dropping the tracking row.
        entity.setId(UUID.randomUUID());
        entity.setOrgId(orgId);
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setSandboxType(sandboxType);
        entity.setExternalId(externalId);
        entity.setStatus("active");
        entity.setLastUsedAt(OffsetDateTime.now());
        entity.setExpiresAt(expiresAt);
        sandboxRepository.save(entity);
        metrics.registerActive(sandboxType);

        log.debug(
                "Registered active sandbox id={} type={} externalId={} for user={} org={}",
                entity.getId(),
                sandboxType,
                externalId,
                userId,
                orgId);
        return entity.getId();
    }

    /**
     * Backwards-compatible registration entry point for callers that have already enforced quota.
     * New SaaS call paths should prefer {@link #registerActive(UUID, UUID, String, String, String,
     * OffsetDateTime, int)} so quota check and active-row creation are serialized in one
     * transaction.
     */
    @Transactional
    public UUID registerActive(
            UUID orgId,
            UUID userId,
            String sessionId,
            String sandboxType,
            String externalId,
            OffsetDateTime expiresAt) {
        return registerActive(
                orgId, userId, sessionId, sandboxType, externalId, expiresAt, Integer.MAX_VALUE);
    }

    /**
     * Marks a sandbox as released (no longer active).
     *
     * @param sandboxId the database ID of the sandbox tracking record
     */
    @Transactional
    public void release(UUID sandboxId) {
        sandboxRepository
                .findById(sandboxId)
                .ifPresent(
                        entity -> {
                            if (!"active".equals(entity.getStatus())) {
                                log.debug(
                                        "Skipping release for sandbox id={} status={}",
                                        sandboxId,
                                        entity.getStatus());
                                return;
                            }
                            entity.setStatus("released");
                            entity.setLastUsedAt(OffsetDateTime.now());
                            sandboxRepository.save(entity);
                            metrics.release(entity.getSandboxType());
                            log.debug(
                                    "Released sandbox id={} externalId={}",
                                    sandboxId,
                                    entity.getExternalId());
                        });
    }

    /**
     * Tenant-scoped manual recovery path for leaked or stuck sandbox tracking rows. This releases
     * quota pressure immediately by moving non-terminal rows to {@code evicted}; backend teardown is
     * still owned by the sandbox provider/lifecycle layer.
     */
    @Transactional
    public Optional<ForceEvictResult> forceEvict(UUID orgId, UUID sandboxId, String reason) {
        return sandboxRepository
                .findById(sandboxId)
                .filter(entity -> orgId.equals(entity.getOrgId()))
                .map(
                        entity -> {
                            String previousStatus = entity.getStatus();
                            boolean changed =
                                    !"released".equals(previousStatus)
                                            && !"evicted".equals(previousStatus);
                            if (changed) {
                                OffsetDateTime now = OffsetDateTime.now();
                                entity.setStatus("evicted");
                                entity.setLastUsedAt(now);
                                entity.setExpiresAt(now);
                                sandboxRepository.save(entity);
                                metrics.forceEvict(entity.getSandboxType());
                                log.warn(
                                        "Force-evicted sandbox id={} externalId={}"
                                                + " previousStatus={} user={} org={} reason={}",
                                        sandboxId,
                                        entity.getExternalId(),
                                        previousStatus,
                                        entity.getUserId(),
                                        entity.getOrgId(),
                                        sanitizeReason(reason));
                            }
                            return new ForceEvictResult(entity, previousStatus, changed);
                        });
    }

    /**
     * Extends the idle lease for a currently active sandbox tracking row. Long-running agent calls
     * periodically refresh this timestamp so the idle eviction job does not mistake an in-flight
     * sandbox for an abandoned one.
     */
    @Transactional
    public void refreshLease(UUID sandboxId, OffsetDateTime expiresAt) {
        sandboxRepository
                .findById(sandboxId)
                .ifPresent(
                        entity -> {
                            if (!"active".equals(entity.getStatus())) {
                                return;
                            }
                            entity.setLastUsedAt(OffsetDateTime.now());
                            entity.setExpiresAt(expiresAt);
                            sandboxRepository.save(entity);
                        });
    }

    /**
     * Finds and marks expired sandboxes as evicted. Called periodically by
     * {@link SandboxEvictionJob}.
     *
     * @return the number of sandboxes evicted
     */
    @Transactional
    public int evictExpired() {
        var expired = sandboxRepository.findExpiredSandboxes(OffsetDateTime.now());
        for (SandboxEntity entity : expired) {
            entity.setStatus("evicted");
            entity.setLastUsedAt(OffsetDateTime.now());
            metrics.evict(entity.getSandboxType());
            log.info(
                    "Evicted expired sandbox id={} externalId={} user={} org={}",
                    entity.getId(),
                    entity.getExternalId(),
                    entity.getUserId(),
                    entity.getOrgId());
        }
        sandboxRepository.saveAll(expired);
        return expired.size();
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        String normalized = reason.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 256) {
            return normalized;
        }
        return normalized.substring(0, 256);
    }
}
