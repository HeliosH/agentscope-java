/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.MarketplaceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for tenant marketplace configuration. */
public interface MarketplaceRepository {

    List<MarketplaceEntity> findByOrgIdOrderByIdAsc(UUID orgId);

    Optional<MarketplaceEntity> findByOrgIdAndMarketplaceId(UUID orgId, String marketplaceId);

    MarketplaceEntity save(MarketplaceEntity marketplace);

    long deleteByOrgIdAndMarketplaceId(UUID orgId, String marketplaceId);
}
