/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceInputMapper;
import io.agentscope.saas.domain.workspace.WorkspaceInputRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceInputRepository implements WorkspaceInputRepository {
    private final WorkspaceInputMapper mapper;

    public MyBatisWorkspaceInputRepository(WorkspaceInputMapper mapper) {
        this.mapper = mapper;
    }

    public List<Input> bindings(Scope scope, int limit) {
        return mapper.bindings(scope, limit);
    }
}
