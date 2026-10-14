/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceSystemMapper;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import io.agentscope.saas.domain.workspace.WorkspaceSystemRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceSystemRepository implements WorkspaceSystemRepository {
    private final WorkspaceSystemMapper mapper;

    public MyBatisWorkspaceSystemRepository(WorkspaceSystemMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Manifest> find(Scope scope) {
        return Optional.ofNullable(mapper.find(scope));
    }

    public List<Entry> entries(Scope scope, int limit) {
        return mapper.entries(scope, limit);
    }

    public long occupied(UUID org, OffsetDateTime now) {
        return mapper.occupied(org, now);
    }

    public void freeze(Scope scope, Manifest manifest, List<Entry> entries) {
        mapper.insert(scope, manifest);
        for (var entry : entries) mapper.insertEntry(scope, entry);
    }
}
