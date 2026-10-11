/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.orchestration;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Operational consumer liveness, separate from business task lease ownership. */
public interface WorkerPresenceRepository {
    void heartbeat(String deployment, UUID worker, String capability, int slots, long ttlSeconds);

    void heartbeat(
            String deployment,
            UUID worker,
            String capability,
            int slots,
            long ttlSeconds,
            long maxArchiveBytes,
            long maxFileBytes);

    void retire(String deployment, UUID worker);

    Presence presence(String deployment, String capability);

    Presence presenceForPolicy(
            String deployment, String capability, long archiveBytes, long fileBytes);

    WaitingRecovery waitingRecovery();

    Optional<RecoveryQueue> ownedRecovery(UUID org, UUID user, UUID agent, UUID run);

    record Presence(long registered, long healthy, long availableSlots) {}

    record WaitingRecovery(long count, OffsetDateTime oldestQueuedAt) {}

    record RecoveryQueue(
            String runStatus,
            boolean cancelRequested,
            String taskStatus,
            String phase,
            String reason,
            int recoveryCount,
            int attemptCount,
            OffsetDateTime nextRecoveryAt,
            OffsetDateTime queuedAt) {}
}
