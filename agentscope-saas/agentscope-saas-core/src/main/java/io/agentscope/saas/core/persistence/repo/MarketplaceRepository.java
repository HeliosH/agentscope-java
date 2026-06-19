/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.core.persistence.repo;

import io.agentscope.saas.core.persistence.entity.MarketplaceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link MarketplaceEntity}, with org-scoped queries. */
public interface MarketplaceRepository extends JpaRepository<MarketplaceEntity, UUID> {

    List<MarketplaceEntity> findByOrgIdOrderByIdAsc(UUID orgId);

    Optional<MarketplaceEntity> findByOrgIdAndMarketplaceId(UUID orgId, String marketplaceId);

    long deleteByOrgIdAndMarketplaceId(UUID orgId, String marketplaceId);
}
