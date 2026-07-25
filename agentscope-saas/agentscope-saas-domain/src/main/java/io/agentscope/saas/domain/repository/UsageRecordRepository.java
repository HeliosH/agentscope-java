/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.UsageRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Domain persistence port for usage metering. */
public interface UsageRecordRepository {

    UsageRecordEntity save(UsageRecordEntity record);

    long countByOrgId(UUID orgId);

    List<UsageAggregate> aggregateUsage(
            UUID orgId, UUID userId, String metric, OffsetDateTime from, OffsetDateTime to);

    record UsageAggregate(
            String metric,
            String model,
            long records,
            long totalValue,
            OffsetDateTime firstRecordedAt,
            OffsetDateTime lastRecordedAt) {

        public String getMetric() {
            return metric;
        }

        public String getModel() {
            return model;
        }

        public long getRecords() {
            return records;
        }

        public long getTotalValue() {
            return totalValue;
        }

        public OffsetDateTime getFirstRecordedAt() {
            return firstRecordedAt;
        }

        public OffsetDateTime getLastRecordedAt() {
            return lastRecordedAt;
        }
    }
}
