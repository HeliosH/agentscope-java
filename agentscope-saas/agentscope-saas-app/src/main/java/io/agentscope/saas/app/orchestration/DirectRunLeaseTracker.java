/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Maintains leases for request-started runs so another worker can recover after process loss. */
@Component
public class DirectRunLeaseTracker {

    private static final Logger log = LoggerFactory.getLogger(DirectRunLeaseTracker.class);

    private final DurableTaskLeaseService leases;
    private final Map<UUID, String> active = new ConcurrentHashMap<>();

    public DirectRunLeaseTracker(DurableTaskLeaseService leases) {
        this.leases = leases;
    }

    public void register(UUID attemptId, String leaseOwner) {
        if (attemptId == null || leaseOwner == null || leaseOwner.isBlank()) {
            return;
        }
        if (!leases.activateDirect(attemptId, leaseOwner)) {
            throw new IllegalStateException("Unable to activate direct run lease " + attemptId);
        }
        active.put(attemptId, leaseOwner);
    }

    public void unregister(UUID attemptId) {
        if (attemptId != null) {
            active.remove(attemptId);
        }
    }

    @Scheduled(
            fixedDelayString = "${saas.orchestration.scheduler-heartbeat-seconds:20}",
            timeUnit = TimeUnit.SECONDS)
    public void heartbeatScheduled() {
        active.forEach(
                (attemptId, owner) -> {
                    try {
                        if (!leases.heartbeat(attemptId, owner)) {
                            active.remove(attemptId, owner);
                            log.warn("Lost direct run lease attempt={}", attemptId);
                        }
                    } catch (RuntimeException error) {
                        log.warn(
                                "Direct run heartbeat failed attempt={}: {}",
                                attemptId,
                                error.getMessage());
                    }
                });
    }
}
