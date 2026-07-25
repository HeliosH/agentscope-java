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
import io.agentscope.saas.domain.orchestration.OrchestrationOutboxMessage;
import io.agentscope.saas.domain.orchestration.OrchestrationOutboxRepository;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher;
import io.agentscope.saas.orchestration.OrchestrationEventDispatcher.OutboxEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lease-based, at-least-once publisher for orchestration state-change events. */
@Component
public class OrchestrationOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationOutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OrchestrationOutboxRepository outboxRepository;
    private final SaasProperties properties;
    private final OrchestrationEventDispatcher dispatcher;
    private final String workerId;

    @Autowired
    public OrchestrationOutboxPublisher(
            OrchestrationOutboxRepository outboxRepository,
            SaasProperties properties,
            OrchestrationEventDispatcher dispatcher) {
        this(outboxRepository, properties, dispatcher, "outbox-" + UUID.randomUUID());
    }

    OrchestrationOutboxPublisher(
            OrchestrationOutboxRepository outboxRepository,
            SaasProperties properties,
            OrchestrationEventDispatcher dispatcher,
            String workerId) {
        this.outboxRepository = outboxRepository;
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
        for (OrchestrationOutboxMessage candidate :
                outboxRepository.findClaimable(now, batchSize, maxAttempts)) {
            if (!claim(candidate.id(), now, leaseSeconds, maxAttempts)) {
                continue;
            }
            summary.claimed++;
            int attemptNo = candidate.attempts() + 1;
            try {
                dispatcher.dispatch(toEvent(candidate));
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

    private boolean claim(UUID id, OffsetDateTime now, long leaseSeconds, int maxAttempts) {
        return outboxRepository.claim(
                id, workerId, now, now.plusSeconds(leaseSeconds), maxAttempts);
    }

    private void markPublished(UUID id) {
        outboxRepository.markPublished(id, workerId, OffsetDateTime.now());
    }

    private void markFailed(UUID id, int attemptNo, int maxAttempts, Throwable error) {
        boolean exhausted = attemptNo >= maxAttempts;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime nextAttempt =
                exhausted ? null : now.plusSeconds(retryDelaySeconds(attemptNo));
        outboxRepository.markFailed(
                id, workerId, nextAttempt, exhausted ? now : null, truncate(errorMessage(error)));
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

    private static OutboxEvent toEvent(OrchestrationOutboxMessage message) {
        return new OutboxEvent(
                message.id(),
                message.orgId(),
                message.aggregateId(),
                message.aggregateType(),
                message.eventType(),
                message.payloadJson(),
                message.createdAt());
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
