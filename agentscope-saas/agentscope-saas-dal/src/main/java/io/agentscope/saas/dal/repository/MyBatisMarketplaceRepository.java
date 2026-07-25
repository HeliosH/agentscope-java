/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.MarketplaceConfigMapper;
import io.agentscope.saas.domain.model.MarketplaceEntity;
import io.agentscope.saas.domain.repository.MarketplaceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant marketplace configuration. */
@Repository
public class MyBatisMarketplaceRepository implements MarketplaceRepository {

    private final MarketplaceConfigMapper mapper;

    public MyBatisMarketplaceRepository(MarketplaceConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<MarketplaceEntity> findByOrgIdOrderByIdAsc(UUID orgId) {
        return mapper.findByOrg(orgId);
    }

    @Override
    public Optional<MarketplaceEntity> findByOrgIdAndMarketplaceId(
            UUID orgId, String marketplaceId) {
        List<MarketplaceEntity> rows = mapper.findByNaturalId(orgId, marketplaceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public MarketplaceEntity save(MarketplaceEntity marketplace) {
        if (mapper.update(marketplace) == 0) {
            int rows = mapper.insert(marketplace);
            if (rows != 1) {
                throw new IllegalStateException(
                        "insert Marketplace "
                                + marketplace.getId()
                                + " affected "
                                + rows
                                + " rows");
            }
        }
        return marketplace;
    }

    @Override
    public long deleteByOrgIdAndMarketplaceId(UUID orgId, String marketplaceId) {
        return mapper.delete(orgId, marketplaceId);
    }
}
