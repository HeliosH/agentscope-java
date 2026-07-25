/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceCatalogMapper;
import io.agentscope.saas.domain.model.FileVersionEntity;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for immutable workspace file versions. */
@Repository
public class MyBatisFileVersionRepository implements FileVersionRepository {

    private final WorkspaceCatalogMapper mapper;

    public MyBatisFileVersionRepository(WorkspaceCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<FileVersionEntity> findByIdAndOrgId(UUID id, UUID orgId) {
        return first(mapper.findVersion(id, orgId));
    }

    @Override
    public Optional<FileVersionEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId) {
        return first(mapper.findOwnedVersion(id, orgId, userId));
    }

    @Override
    public Optional<FileVersionEntity> findFirstByFileIdOrderByVersionNoDesc(UUID fileId) {
        return first(mapper.findLatestVersion(fileId));
    }

    @Override
    public List<FileVersionEntity> findByFileIdAndOrgIdAndUserIdOrderByVersionNoDesc(
            UUID fileId, UUID orgId, UUID userId) {
        return mapper.findVersions(fileId, orgId, userId);
    }

    @Override
    public List<FileVersionEntity> findAllById(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : mapper.findVersionsByIds(ids);
    }

    @Override
    public long maxVersionNo(UUID fileId) {
        return mapper.maxVersionNo(fileId);
    }

    @Override
    public long currentUsageByUser(UUID orgId, UUID userId) {
        return mapper.currentUsageByUser(orgId, userId);
    }

    @Override
    public long currentUsageByOrg(UUID orgId) {
        return mapper.currentUsageByOrg(orgId);
    }

    @Override
    public FileVersionEntity save(FileVersionEntity version) {
        int rows = mapper.insertVersion(version);
        if (rows != 1) {
            throw new IllegalStateException(
                    "insert FileVersion " + version.getId() + " affected " + rows + " rows");
        }
        return version;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
