/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.SandboxUsageMapper;
import io.agentscope.saas.domain.sandbox.SandboxUsageRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisSandboxUsageRepository implements SandboxUsageRepository {
    private final SandboxUsageMapper mapper;

    public MyBatisSandboxUsageRepository(SandboxUsageMapper mapper) {
        this.mapper = mapper;
    }

    public boolean authority(Scope scope) {
        return mapper.authority(scope) == 1;
    }

    public long countOccupied(UUID org, UUID user) {
        return mapper.countOccupied(org, user);
    }

    public boolean managedBackend(UUID org, UUID user, String provider, String externalId) {
        return mapper.managedBackend(org, user, provider, externalId) > 0;
    }

    public boolean slotOccupied(
            UUID org, UUID user, String provider, String deployment, String slot) {
        return mapper.slotOccupied(org, user, provider, deployment, slot) > 0;
    }

    public void insert(Resource r) {
        require(mapper.insertResource(r));
    }

    public Optional<Resource> lock(UUID org, UUID user, UUID id) {
        return mapper.lock(org, user, id).stream().findFirst();
    }

    public Optional<Resource> find(UUID org, UUID user, UUID id) {
        return mapper.find(org, user, id).stream().findFirst();
    }

    public void insert(Reference ref) {
        require(mapper.insertReference(ref));
    }

    public Optional<Reference> reference(UUID org, UUID user, UUID id) {
        return mapper.reference(org, user, id).stream().findFirst();
    }

    public long activeReferences(UUID id, UUID exceptId) {
        return mapper.activeReferences(id, exceptId);
    }

    public boolean activate(Resource r, String external) {
        return mapper.activate(r, external) == 1;
    }

    public boolean state(Resource r, String status, UUID token, OffsetDateTime until) {
        return mapper.state(r, status, token, until) == 1;
    }

    public boolean heartbeat(Reference ref, OffsetDateTime until) {
        return mapper.heartbeat(ref, until) == 1;
    }

    public boolean closeReference(Reference ref) {
        return mapper.closeReference(ref) == 1;
    }

    public boolean finish(UUID org, UUID id, UUID token, boolean success) {
        return mapper.finish(org, id, token, success ? "RELEASED" : "RELEASE_FAILED") == 1;
    }

    private static void require(int count) {
        if (count != 1) throw new IllegalStateException("SANDBOX_USAGE_WRITE_REJECTED");
    }
}
