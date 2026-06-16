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
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public SandboxBroker(SandboxRepository sandboxRepository) {
        this.sandboxRepository = sandboxRepository;
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
     * @return the database ID of the newly created tracking record
     */
    @Transactional
    public UUID registerActive(
            UUID orgId,
            UUID userId,
            String sessionId,
            String sandboxType,
            String externalId,
            OffsetDateTime expiresAt) {

        SandboxEntity entity = new SandboxEntity();
        entity.setOrgId(orgId);
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setSandboxType(sandboxType);
        entity.setExternalId(externalId);
        entity.setStatus("active");
        entity.setLastUsedAt(OffsetDateTime.now());
        entity.setExpiresAt(expiresAt);
        sandboxRepository.save(entity);

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
                            entity.setStatus("released");
                            entity.setLastUsedAt(OffsetDateTime.now());
                            sandboxRepository.save(entity);
                            log.debug(
                                    "Released sandbox id={} externalId={}",
                                    sandboxId,
                                    entity.getExternalId());
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
}
