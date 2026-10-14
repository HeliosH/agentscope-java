/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceInputMapper;
import io.agentscope.saas.domain.workspace.WorkspaceInputRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceInputRepository implements WorkspaceInputRepository {
    @Override
    public List<Input> parentBindings(Scope scope, int limit) {
        return mapper.parentBindings(scope, limit);
    }

    private final WorkspaceInputMapper mapper;

    public MyBatisWorkspaceInputRepository(WorkspaceInputMapper mapper) {
        this.mapper = mapper;
    }

    public List<Input> bindings(Scope scope, int limit) {
        return mapper.bindings(scope, limit);
    }

    public List<Input> dependencies(Scope scope, int limit) {
        return mapper.dependencies(scope, limit);
    }

    public Optional<Frozen> frozen(Scope scope) {
        return Optional.ofNullable(mapper.frozen(scope));
    }

    public List<Input> frozenBindings(Scope scope, int limit) {
        return mapper.frozenBindings(scope, limit);
    }

    public void freeze(Scope scope, Frozen manifest, List<Input> files) {
        mapper.insertManifest(scope, manifest);
        for (var file : files) mapper.insertEntry(scope, file);
    }

    public Optional<Input> ownedVersion(Scope scope, java.util.UUID file, java.util.UUID version) {
        return Optional.ofNullable(mapper.ownedVersion(scope, file, version));
    }

    public List<Input> additions(Scope scope, int limit) {
        return mapper.additions(scope, limit);
    }

    public void append(Scope scope, Input file) {
        mapper.append(scope, file, mapper.nextAddition(scope));
    }

    public List<Input> search(Scope scope, String pattern, String afterPath, int limit) {
        return mapper.search(scope, pattern, afterPath, limit);
    }
}
