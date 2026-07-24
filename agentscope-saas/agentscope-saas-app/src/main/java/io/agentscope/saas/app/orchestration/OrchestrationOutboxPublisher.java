/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lease-based, at-least-once publisher for orchestration state-change events. */
@Component
public class OrchestrationOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationOutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final SaasProperties properties;
    private final OrchestrationEventDispatcher dispatcher;
    private final String workerId;

    @Autowired
    public OrchestrationOutboxPublisher(
            @Qualifier("adminDataSource") DataSource adminDataSource,
            SaasProperties properties,
            OrchestrationEventDispatcher dispatcher) {
        this(
                new JdbcTemplate(adminDataSource),
                properties,
                dispatcher,
                "outbox-" + UUID.randomUUID());
    }

    OrchestrationOutboxPublisher(
            JdbcTemplate jdbc,
            SaasProperties properties,
            OrchestrationEventDispatcher dispatcher,
            String workerId) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${saas.orchestration.outbox-fixed-delay-millis:1000}",
            timeUnit = TimeUnit.MILLISECONDS)
    public void publishScheduled() {
        if (!properties.getOrchestration().isEnabled()
                || !properties.getOrchestration().isOutboxEnabled()) {
            return;
        }
        try {
            DeliverySummary summary = publishBatch();
            if (summary.claimed() > 0) {
                log.debug(
                        "Orchestration Outbox delivery claimed={} published={} failed={} "
                                + "deadLettered={}",
                        summary.claimed(),
                        summary.published(),
                        summary.failed(),
                        summary.deadLettered());
            }
        } catch (RuntimeException e) {
            log.warn("Orchestration Outbox scan failed: {}", errorMessage(e));
        }
    }

    DeliverySummary publishBatch() {
        SaasProperties.Orchestration config = properties.getOrchestration();
        int batchSize = Math.max(1, config.getOutboxBatchSize());
        int maxAttempts = Math.max(1, config.getOutboxMaxAttempts());
        long leaseSeconds = Math.max(1L, config.getOutboxLeaseSeconds());
        OffsetDateTime now = OffsetDateTime.now();

        MutableSummary summary = new MutableSummary();
        for (Candidate candidate : loadCandidates(now, batchSize, maxAttempts)) {
            if (!claim(candidate.id(), now, leaseSeconds, maxAttempts)) {
                continue;
            }
            summary.claimed++;
            int attemptNo = candidate.attempts() + 1;
            try {
                dispatcher.dispatch(candidate.toEvent());
                markPublished(candidate.id());
                summary.published++;
            } catch (Exception e) {
                boolean exhausted = attemptNo >= maxAttempts;
                markFailed(candidate.id(), attemptNo, maxAttempts, e);
                summary.failed++;
                if (exhausted) {
                    summary.deadLettered++;
                }
                log.warn(
                        "Orchestration Outbox delivery failed event={} type={} attempt={}/{}: {}",
                        candidate.id(),
                        candidate.eventType(),
                        attemptNo,
                        maxAttempts,
                        errorMessage(e));
            }
        }
        return summary.toImmutable();
    }

    private List<Candidate> loadCandidates(OffsetDateTime now, int batchSize, int maxAttempts) {
        return jdbc.query(
                """
                SELECT id, org_id, aggregate_id, aggregate_type, event_type, payload_json,
                       created_at, attempts
                  FROM orchestration_outbox
                 WHERE published_at IS NULL
                   AND dead_lettered_at IS NULL
                   AND attempts < ?
                   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                   AND (locked_until IS NULL OR locked_until < ?)
                 ORDER BY created_at ASC, id ASC
                 LIMIT ?
                """,
                this::mapCandidate,
                maxAttempts,
                now,
                now,
                batchSize);
    }

    private Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("attempts"));
    }

    private boolean claim(UUID id, OffsetDateTime now, long leaseSeconds, int maxAttempts) {
        int updated =
                jdbc.update(
                        """
                        UPDATE orchestration_outbox
                           SET locked_by = ?,
                               locked_until = ?,
                               attempts = attempts + 1,
                               last_error = NULL
                         WHERE id = ?
                           AND published_at IS NULL
                           AND dead_lettered_at IS NULL
                           AND attempts < ?
                           AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                           AND (locked_until IS NULL OR locked_until < ?)
                        """,
                        workerId,
                        now.plusSeconds(leaseSeconds),
                        id,
                        maxAttempts,
                        now,
                        now);
        return updated == 1;
    }

    private void markPublished(UUID id) {
        jdbc.update(
                """
                UPDATE orchestration_outbox
                   SET published_at = ?,
                       locked_by = NULL,
                       locked_until = NULL,
                       next_attempt_at = NULL,
                       last_error = NULL
                 WHERE id = ?
                   AND locked_by = ?
                   AND published_at IS NULL
                """,
                OffsetDateTime.now(),
                id,
                workerId);
    }

    private void markFailed(UUID id, int attemptNo, int maxAttempts, Throwable error) {
        boolean exhausted = attemptNo >= maxAttempts;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime nextAttempt =
                exhausted ? null : now.plusSeconds(retryDelaySeconds(attemptNo));
        jdbc.update(
                """
                UPDATE orchestration_outbox
                   SET locked_by = NULL,
                       locked_until = NULL,
                       next_attempt_at = ?,
                       dead_lettered_at = ?,
                       last_error = ?
                 WHERE id = ?
                   AND locked_by = ?
                   AND published_at IS NULL
                """,
                nextAttempt,
                exhausted ? now : null,
                truncate(errorMessage(error)),
                id,
                workerId);
    }

    long retryDelaySeconds(int attemptNo) {
        SaasProperties.Orchestration config = properties.getOrchestration();
        long base = Math.max(1L, config.getOutboxRetryBaseSeconds());
        long maximum = Math.max(base, config.getOutboxRetryMaxSeconds());
        long delay = base;
        for (int i = 1; i < attemptNo && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return delay;
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    record Candidate(
            UUID id,
            UUID orgId,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payloadJson,
            OffsetDateTime createdAt,
            int attempts) {

        OutboxEvent toEvent() {
            return new OutboxEvent(
                    id, orgId, aggregateId, aggregateType, eventType, payloadJson, createdAt);
        }
    }

    public record DeliverySummary(int claimed, int published, int failed, int deadLettered) {}

    private static final class MutableSummary {
        private int claimed;
        private int published;
        private int failed;
        private int deadLettered;

        private DeliverySummary toImmutable() {
            return new DeliverySummary(claimed, published, failed, deadLettered);
        }
    }
}
