/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.FilePublicationGcMapper;
import io.agentscope.saas.dal.mybatis.tenant.FilePublicationMapper;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisFilePublicationRepository implements FilePublicationRepository {
    private final FilePublicationMapper tenant;
    private final FilePublicationGcMapper admin;

    public MyBatisFilePublicationRepository(
            FilePublicationMapper tenant, FilePublicationGcMapper admin) {
        this.tenant = tenant;
        this.admin = admin;
    }

    @Override
    public void stage(Publication p) {
        if (tenant.stage(p) != 1)
            throw new IllegalStateException("File publication reservation failed");
    }

    @Override
    public void saveIntent(Publication p, Intent i, boolean recoverable, OffsetDateTime deadline) {
        if (!p.id().equals(i.publicationId())
                || tenant.saveIntent(p, i) != 1
                || tenant.enableRecovery(p, recoverable, deadline) != 1)
            throw new IllegalStateException("File publication intent not committed");
    }

    @Override
    public Optional<Intent> intent(UUID org, UUID user, UUID id) {
        return tenant.intent(org, user, id).stream().findFirst();
    }

    @Override
    public Optional<Recovery> recovery(UUID org, UUID user, UUID id) {
        return tenant.recovery(org, user, id).stream().findFirst();
    }

    @Override
    public boolean lockExecution(Publication p, Intent i, OffsetDateTime now) {
        return p.runId() == null
                || (i.execution() != null
                        && !tenant.lockExecutionRun(p).isEmpty()
                        && !tenant.lockExecutionAttempt(p, i, now).isEmpty());
    }

    @Override
    public List<Publication> recoveryCandidates(OffsetDateTime now, int attempts, int limit) {
        return admin.recoveryCandidates(now, attempts, limit);
    }

    @Override
    public int reclaimRecovery(
            UUID org,
            UUID user,
            UUID id,
            UUID token,
            OffsetDateTime now,
            OffsetDateTime until,
            int attempts) {
        return tenant.reclaimRecovery(org, user, id, token, now, until, attempts);
    }

    @Override
    public int publishRecovered(
            UUID org, UUID user, UUID id, UUID token, UUID version, OffsetDateTime now) {
        return tenant.publishRecovered(org, user, id, token, version, now);
    }

    @Override
    public int deferRecovery(
            UUID org, UUID user, UUID id, UUID token, OffsetDateTime now, OffsetDateTime retry) {
        return tenant.deferRecovery(org, user, id, token, now, retry);
    }

    @Override
    public int abandonRecovery(UUID org, UUID user, UUID id, UUID token, OffsetDateTime now) {
        return tenant.abandonRecovery(org, user, id, token, now);
    }

    @Override
    public long reserved(UUID org, UUID user, UUID excluding, OffsetDateTime now) {
        return tenant.reserved(org, user, excluding, now);
    }

    @Override
    public boolean pathBusy(UUID org, UUID user, String path, OffsetDateTime now) {
        return tenant.pathBusy(org, user, path, now) > 0;
    }

    @Override
    public int stored(UUID org, UUID user, UUID id, OffsetDateTime now) {
        return tenant.stored(org, user, id, now);
    }

    @Override
    public Optional<Publication> lockOwned(UUID org, UUID user, UUID id) {
        return tenant.lockOwned(org, user, id).stream().findFirst();
    }

    @Override
    public int published(UUID org, UUID user, UUID id, UUID version, OffsetDateTime now) {
        return tenant.published(org, user, id, version, now);
    }

    @Override
    public int abort(UUID org, UUID user, UUID id, OffsetDateTime eligible) {
        return tenant.abort(org, user, id, eligible);
    }

    @Override
    public List<Publication> candidates(OffsetDateTime now, int attempts, int limit) {
        return admin.candidates(now, attempts, limit);
    }

    @Override
    public int claim(UUID id, UUID token, OffsetDateTime now, OffsetDateTime until, int attempts) {
        return admin.claim(id, token, now, until, attempts);
    }

    @Override
    public long references(UUID id) {
        return admin.references(id);
    }

    @Override
    public int collected(UUID id, UUID token, OffsetDateTime recheck) {
        return admin.collected(id, token, recheck);
    }

    @Override
    public int failed(UUID id, UUID token, OffsetDateTime retry) {
        return admin.failed(id, token, retry);
    }
}
