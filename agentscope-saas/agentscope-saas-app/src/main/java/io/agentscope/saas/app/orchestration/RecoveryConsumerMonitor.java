/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryConsumerMonitor {
    private static final Logger log = LoggerFactory.getLogger(RecoveryConsumerMonitor.class);
    private final WorkerPresenceRepository repository;
    private final RecoveryCapability capability;
    private final SaasProperties properties;
    private final AtomicLong healthy = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();

    public RecoveryConsumerMonitor(
            WorkerPresenceRepository repository,
            RecoveryCapability capability,
            SaasProperties properties,
            MeterRegistry metrics) {
        this.repository = repository;
        this.capability = capability;
        this.properties = properties;
        Gauge.builder("saas.runtime.recovery.consumer.healthy", healthy, AtomicLong::get)
                .register(metrics);
        Gauge.builder("saas.runtime.recovery.waiting.tasks", queued, AtomicLong::get)
                .register(metrics);
        Gauge.builder("saas.runtime.recovery.oldest.wait.seconds", oldest, AtomicLong::get)
                .register(metrics);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void check() {
        if (!properties.getOrchestration().isEnabled()) return;
        try {
            healthy.set(capability.snapshot().healthy() ? 1 : 0);
            var waiting = repository.waitingRecovery();
            queued.set(waiting.count());
            long age =
                    waiting.oldestQueuedAt() == null
                            ? 0
                            : Math.max(
                                    0,
                                    Duration.between(waiting.oldestQueuedAt(), OffsetDateTime.now())
                                            .getSeconds());
            oldest.set(age);
            if (age >= properties.getOrchestration().getRecoveryQueueSlaSeconds())
                log.warn(
                        "Recovery queue SLA exceeded: waitingTasks={} oldestWaitSeconds={}"
                                + " consumerHealthy={}",
                        waiting.count(),
                        age,
                        healthy.get());
        } catch (RuntimeException error) {
            healthy.set(0);
            log.warn(
                    "Unable to observe durable recovery consumers: {}",
                    error.getClass().getSimpleName());
        }
    }
}
