/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.WorkerPresenceMapper;
import io.agentscope.saas.dal.mybatis.tenant.RecoveryQueueMapper;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkerPresenceRepository implements WorkerPresenceRepository {
    private final WorkerPresenceMapper admin;
    private final RecoveryQueueMapper tenant;

    public MyBatisWorkerPresenceRepository(WorkerPresenceMapper admin, RecoveryQueueMapper tenant) {
        this.admin = admin;
        this.tenant = tenant;
    }

    @Override
    public void heartbeat(String deployment, UUID worker, String capability, int slots, long ttl) {
        heartbeat(deployment, worker, capability, slots, ttl, 0, 0);
    }

    @Override
    public void heartbeat(
            String deployment,
            UUID worker,
            String capability,
            int slots,
            long ttl,
            long archiveBytes,
            long fileBytes) {
        if (admin.heartbeat(deployment, worker, capability, slots, ttl, archiveBytes, fileBytes)
                == 1) return;
        try {
            admin.register(deployment, worker, capability, slots, ttl, archiveBytes, fileBytes);
        } catch (RuntimeException registrationFailure) {
            // Resolve duplicate or ambiguous registration only by proving the same owned row.
            try {
                if (admin.heartbeat(
                                deployment, worker, capability, slots, ttl, archiveBytes, fileBytes)
                        != 1) throw registrationFailure;
            } catch (RuntimeException retryFailure) {
                if (retryFailure != registrationFailure)
                    registrationFailure.addSuppressed(retryFailure);
                throw registrationFailure;
            }
        }
    }

    @Override
    public void retire(String deployment, UUID worker) {
        admin.retire(deployment, worker);
    }

    @Override
    public Presence presence(String deployment, String capability) {
        return admin.presence(deployment, capability, 0, 0);
    }

    @Override
    public Presence presenceForPolicy(
            String deployment, String capability, long archiveBytes, long fileBytes) {
        return admin.presence(deployment, capability, archiveBytes, fileBytes);
    }

    @Override
    public WaitingRecovery waitingRecovery() {
        return admin.waitingRecovery();
    }

    @Override
    public Optional<RecoveryQueue> ownedRecovery(UUID org, UUID user, UUID agent, UUID run) {
        return tenant.ownedRecovery(org, user, agent, run).stream().findFirst();
    }
}
