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

import io.agentscope.saas.core.persistence.entity.UsageRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link UsageRecordEntity} (metering). */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    long countByOrgId(UUID orgId);

    @Query(
            """
            SELECT u.metric AS metric,
                   u.model AS model,
                   COUNT(u) AS records,
                   COALESCE(SUM(u.value), 0) AS totalValue,
                   MIN(u.recordedAt) AS firstRecordedAt,
                   MAX(u.recordedAt) AS lastRecordedAt
            FROM UsageRecordEntity u
            WHERE u.orgId = :orgId
              AND (:userId IS NULL OR u.userId = :userId)
              AND (:metric IS NULL OR u.metric = :metric)
              AND (:fromTs IS NULL OR u.recordedAt >= :fromTs)
              AND (:toTs IS NULL OR u.recordedAt <= :toTs)
            GROUP BY u.metric, u.model
            ORDER BY u.metric ASC, u.model ASC
            """)
    List<UsageAggregate> aggregateUsage(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("metric") String metric,
            @Param("fromTs") OffsetDateTime from,
            @Param("toTs") OffsetDateTime to);

    interface UsageAggregate {

        String getMetric();

        String getModel();

        long getRecords();

        long getTotalValue();

        OffsetDateTime getFirstRecordedAt();

        OffsetDateTime getLastRecordedAt();
    }
}
