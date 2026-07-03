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
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import io.agentscope.saas.sandbox.SandboxMetrics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_EVICTED = "evicted";
    private static final String RELEASE_PENDING = "pending";
    private static final String RELEASE_FAILED = "failed";
    private static final String RELEASE_TERMINATING = "terminating";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final SaasProperties properties;
    private final SandboxBackendTerminator terminator;
    private final SandboxMetrics metrics;

    @Autowired
    public SandboxReconciliationJob(
            @Qualifier("adminDataSource") DataSource adminDataSource,
            SaasProperties properties,
            SandboxBackendTerminator terminator,
            SandboxMetrics metrics) {
        this(
                new JdbcTemplate(adminDataSource),
                properties,
                terminator,
                metrics != null ? metrics : SandboxMetrics.noop());
    }

    SandboxReconciliationJob(
            JdbcTemplate jdbc,
            SaasProperties properties,
            SandboxBackendTerminator terminator,
            SandboxMetrics metrics) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.terminator = terminator != null ? terminator : SandboxBackendTerminator.unsupported();
        this.metrics = metrics != null ? metrics : SandboxMetrics.noop();
    }

    @Scheduled(fixedDelayString = "${saas.sandbox.reconciliation-fixed-delay-seconds:300}000")
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
                                + "backendSkipped={} backendFailed={}",
                        summary.expiredActive(),
                        summary.backendReleased(),
                        summary.backendSkipped(),
                        summary.backendFailed());
            }
        } catch (RuntimeException e) {
            log.warn("Sandbox reconciliation scan failed: {}", e.getMessage());
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
        for (SandboxCandidate candidate : findExpiredActive(staleBefore, batchSize)) {
            if (markExpiredActiveEvicted(candidate.id())) {
                metrics.evict(candidate.sandboxType());
                summary.expiredActive++;
                terminateAndRecord(candidate, maxAttempts, summary);
            }
        }

        int remaining = Math.max(0, batchSize - summary.expiredActive);
        if (remaining > 0) {
            for (SandboxCandidate candidate :
                    findBackendReleaseCandidates(remaining, maxAttempts)) {
                if (claimBackendRelease(candidate.id(), maxAttempts)) {
                    terminateAndRecord(candidate, maxAttempts, summary);
                }
            }
        }
        return summary.toImmutable();
    }

    private List<SandboxCandidate> findExpiredActive(OffsetDateTime staleBefore, int limit) {
        return jdbc.query(
                """
                SELECT id, org_id, user_id, sandbox_type, external_id
                  FROM sandboxes
                 WHERE status = ?
                   AND expires_at IS NOT NULL
                   AND expires_at < ?
                 ORDER BY expires_at ASC
                 LIMIT ?
                """,
                this::mapCandidate,
                STATUS_ACTIVE,
                staleBefore,
                limit);
    }

    private List<SandboxCandidate> findBackendReleaseCandidates(int limit, int maxAttempts) {
        return jdbc.query(
                """
                SELECT id, org_id, user_id, sandbox_type, external_id
                  FROM sandboxes
                 WHERE status IN ('evicted', 'released')
                   AND external_id IS NOT NULL
                   AND TRIM(external_id) <> ''
                   AND COALESCE(backend_release_attempts, 0) < ?
                   AND (
                        backend_release_status IS NULL
                        OR backend_release_status IN (?, ?)
                   )
                 ORDER BY last_used_at ASC, created_at ASC
                 LIMIT ?
                """,
                this::mapCandidate,
                maxAttempts,
                RELEASE_PENDING,
                RELEASE_FAILED,
                limit);
    }

    private SandboxCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new SandboxCandidate(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("sandbox_type"),
                rs.getString("external_id"));
    }

    private boolean markExpiredActiveEvicted(UUID id) {
        int updated =
                jdbc.update(
                        """
                        UPDATE sandboxes
                           SET status = ?,
                               last_used_at = ?,
                               expires_at = ?,
                               backend_release_status = ?,
                               backend_release_error = NULL
                         WHERE id = ?
                           AND status = ?
                        """,
                        STATUS_EVICTED,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        RELEASE_PENDING,
                        id,
                        STATUS_ACTIVE);
        return updated == 1;
    }

    private boolean claimBackendRelease(UUID id, int maxAttempts) {
        int updated =
                jdbc.update(
                        """
                        UPDATE sandboxes
                           SET backend_release_status = ?,
                               backend_release_error = NULL
                         WHERE id = ?
                           AND status IN ('evicted', 'released')
                           AND COALESCE(backend_release_attempts, 0) < ?
                           AND (
                                backend_release_status IS NULL
                                OR backend_release_status IN (?, ?)
                           )
                        """,
                        RELEASE_TERMINATING,
                        id,
                        maxAttempts,
                        RELEASE_PENDING,
                        RELEASE_FAILED);
        return updated == 1;
    }

    private void terminateAndRecord(
            SandboxCandidate candidate, int maxAttempts, MutableSummary summary) {
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
        jdbc.update(
                """
                UPDATE sandboxes
                   SET backend_release_status = ?,
                       backend_release_attempts = COALESCE(backend_release_attempts, 0) + ?,
                       backend_released_at = CASE WHEN ? THEN ? ELSE backend_released_at END,
                       backend_release_error = ?
                 WHERE id = ?
                """,
                result.status(),
                attemptIncrement,
                result.succeeded(),
                releasedAt,
                result.succeeded() ? null : truncate(result.message()),
                id);
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

    record ReconciliationSummary(
            int expiredActive, int backendReleased, int backendSkipped, int backendFailed) {
        int total() {
            return expiredActive + backendReleased + backendSkipped + backendFailed;
        }
    }

    record SandboxCandidate(
            UUID id, UUID orgId, UUID userId, String sandboxType, String externalId) {}

    private static final class MutableSummary {
        private int expiredActive;
        private int backendReleased;
        private int backendSkipped;
        private int backendFailed;

        private ReconciliationSummary toImmutable() {
            return new ReconciliationSummary(
                    expiredActive, backendReleased, backendSkipped, backendFailed);
        }
    }
}
