/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.ModelDefinitionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for organization-managed model definitions. */
public interface ModelDefinitionRepository {

    List<ModelDefinitionEntity> findByOrgIdOrderByModelId(UUID orgId);

    Optional<ModelDefinitionEntity> findByOrgIdAndModelId(UUID orgId, String modelId);

    ModelDefinitionEntity save(ModelDefinitionEntity definition);

    void clearDefault(UUID orgId, UUID exceptId);

    long deleteByOrgIdAndModelId(UUID orgId, String modelId);
}
