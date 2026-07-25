/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.TierPolicyEntity;
import java.util.List;
import java.util.Optional;

/** Domain persistence port for enterprise quota tier policies. */
public interface TierPolicyRepository {

    Optional<TierPolicyEntity> findById(String tier);

    boolean existsById(String tier);

    List<TierPolicyEntity> findAll();

    TierPolicyEntity save(TierPolicyEntity policy);
}
