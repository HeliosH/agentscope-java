/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.ModelDefinitionMapper;
import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import io.agentscope.saas.domain.repository.ModelDefinitionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis repository adapter for organization-managed model definitions. */
@Repository
public class MyBatisModelDefinitionRepository implements ModelDefinitionRepository {

    private final ModelDefinitionMapper mapper;

    public MyBatisModelDefinitionRepository(ModelDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ModelDefinitionEntity> findByOrgIdOrderByModelId(UUID orgId) {
        return mapper.findByOrg(orgId);
    }

    @Override
    public Optional<ModelDefinitionEntity> findByOrgIdAndModelId(UUID orgId, String modelId) {
        List<ModelDefinitionEntity> rows = mapper.findByNaturalId(orgId, modelId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public ModelDefinitionEntity save(ModelDefinitionEntity definition) {
        boolean exists =
                findByOrgIdAndModelId(definition.getOrgId(), definition.getModelId()).isPresent();
        if (!exists) {
            if (mapper.insert(definition) != 1) {
                throw new IllegalStateException(
                        "Failed to insert model " + definition.getModelId());
            }
            return definition;
        }
        if (mapper.update(definition) != 1) {
            throw new IllegalStateException(
                    "Model definition was changed concurrently: " + definition.getModelId());
        }
        definition.setVersion(definition.getVersion() + 1);
        return definition;
    }

    @Override
    public void clearDefault(UUID orgId, UUID exceptId) {
        mapper.clearDefault(orgId, exceptId);
    }

    @Override
    public long deleteByOrgIdAndModelId(UUID orgId, String modelId) {
        return mapper.delete(orgId, modelId);
    }
}
