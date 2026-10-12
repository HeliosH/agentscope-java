/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.LegacyArchiveImportMapper;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisLegacyArchiveImportRepository implements LegacyArchiveImportRepository {
    private final LegacyArchiveImportMapper mapper;

    public MyBatisLegacyArchiveImportRepository(LegacyArchiveImportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(Job job) {
        mapper.create(job);
    }

    @Override
    public Optional<Job> findBySource(Owner owner, UUID versionId) {
        return mapper.findBySource(owner, versionId).stream().findFirst();
    }

    @Override
    public Optional<Job> find(Owner owner, UUID id) {
        return mapper.find(owner, id).stream().findFirst();
    }

    @Override
    public Optional<Job> lock(Owner owner, UUID id) {
        return mapper.lock(owner, id).stream().findFirst();
    }

    @Override
    public void deleteSession(UUID orgId, UUID userId, UUID sessionId) {
        mapper.deleteSession(orgId, userId, sessionId);
    }

    @Override
    public void insert(Mapping mapping) {
        if (mapper.insert(mapping) != 1)
            throw new IllegalStateException("LEGACY_IMPORT_MAPPING_FAILED");
    }

    @Override
    public boolean advance(
            Job expected,
            long offset,
            long lines,
            long imported,
            long skipped,
            String digestChain,
            boolean complete) {
        return mapper.advance(
                        expected,
                        offset,
                        lines,
                        imported,
                        skipped,
                        digestChain,
                        complete ? "COMPLETE" : "READY")
                == 1;
    }

    @Override
    public List<Mapping> mappings(Owner owner, UUID jobId, long afterLine, int limit) {
        if (afterLine < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("Invalid legacy mapping window");
        return mapper.mappings(owner, jobId, afterLine, limit);
    }

    @Override
    public Proof proof(Owner owner, UUID jobId) {
        return mapper.proof(owner, jobId);
    }
}
