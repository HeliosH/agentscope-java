/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.SandboxLeaseData;
import io.agentscope.saas.dal.mybatis.tenant.SandboxLeaseMapper;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant-scoped orchestration sandbox leases. */
@Repository
public class MyBatisSandboxLeaseRepository implements SandboxLeaseRepository {

    private final SandboxLeaseMapper mapper;

    public MyBatisSandboxLeaseRepository(SandboxLeaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int insert(NewSandboxLease lease) {
        return mapper.insert(lease);
    }

    @Override
    public Optional<SandboxLease> findById(UUID leaseId, UUID orgId) {
        return one(mapper.findById(leaseId, orgId));
    }

    @Override
    public Optional<SandboxLease> findByAttemptId(UUID attemptId, UUID orgId) {
        return one(mapper.findByAttemptId(attemptId, orgId));
    }

    @Override
    public int activate(
            UUID leaseId,
            UUID orgId,
            String providerSandboxId,
            String providerStateJson,
            OffsetDateTime heartbeatAt,
            OffsetDateTime leaseExpiresAt) {
        return mapper.activate(
                leaseId, orgId, providerSandboxId, providerStateJson, heartbeatAt, leaseExpiresAt);
    }

    @Override
    public int heartbeat(
            UUID leaseId, UUID orgId, OffsetDateTime heartbeatAt, OffsetDateTime leaseExpiresAt) {
        return mapper.heartbeat(leaseId, orgId, heartbeatAt, leaseExpiresAt);
    }

    @Override
    public int checkpoint(
            UUID leaseId, UUID orgId, String workspaceSnapshotUri, String workspaceVersion) {
        return mapper.checkpoint(leaseId, orgId, workspaceSnapshotUri, workspaceVersion);
    }

    @Override
    public int release(UUID leaseId, UUID orgId, OffsetDateTime releasedAt) {
        return mapper.release(leaseId, orgId, releasedAt);
    }

    @Override
    public int releaseAfterProvisioningFailure(
            UUID leaseId, UUID orgId, OffsetDateTime releasedAt, String error) {
        return mapper.releaseAfterProvisioningFailure(leaseId, orgId, releasedAt, error);
    }

    private static Optional<SandboxLease> one(List<SandboxLeaseData> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(toDomain(rows.get(0)));
    }

    private static SandboxLease toDomain(SandboxLeaseData row) {
        return new SandboxLease(
                row.id(),
                row.orgId(),
                row.userId(),
                row.runId(),
                row.taskId(),
                row.attemptId(),
                row.providerId(),
                row.providerSandboxId(),
                row.providerStateJson(),
                row.imageOrTemplate(),
                row.capabilitiesJson(),
                row.workspaceSnapshotUri(),
                row.workspaceVersion(),
                row.status(),
                row.leaseOwner(),
                row.leaseExpiresAt(),
                row.lastHeartbeatAt(),
                row.createdAt(),
                row.releasedAt(),
                row.releaseError());
    }
}
