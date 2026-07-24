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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Requeues retryable tasks after a durable worker lease expires. */
@Component
public class DurableTaskLeaseRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(DurableTaskLeaseRecoveryJob.class);

    private final DurableTaskLeaseService leases;
    private final SaasProperties properties;

    public DurableTaskLeaseRecoveryJob(DurableTaskLeaseService leases, SaasProperties properties) {
        this.leases = leases;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.orchestration.scheduler-recovery-fixed-delay-seconds:20}",
            timeUnit = TimeUnit.SECONDS)
    public void recoverScheduled() {
        if (!properties.getOrchestration().isEnabled()
                || !properties.getOrchestration().isSchedulerEnabled()) {
            return;
        }
        try {
            int recovered =
                    leases.recoverExpired(
                            Math.max(1, properties.getOrchestration().getSchedulerBatchSize()));
            if (recovered > 0) {
                log.warn("Recovered {} expired durable task lease(s)", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("Durable task lease recovery failed: {}", e.getMessage());
        }
    }
}
