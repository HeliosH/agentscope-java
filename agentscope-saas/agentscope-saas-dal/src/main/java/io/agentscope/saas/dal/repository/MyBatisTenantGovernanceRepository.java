/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.TenantGovernanceMapper;
import io.agentscope.saas.domain.model.AuditLogEntity;
import io.agentscope.saas.domain.model.UsageRecordEntity;
import io.agentscope.saas.domain.repository.AuditLogRepository;
import io.agentscope.saas.domain.repository.UsageRecordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for tenant audit and usage governance ports. */
@Repository
public class MyBatisTenantGovernanceRepository
        implements AuditLogRepository, UsageRecordRepository {

    private final TenantGovernanceMapper mapper;

    public MyBatisTenantGovernanceRepository(TenantGovernanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuditLogEntity save(AuditLogEntity event) {
        requireOne(mapper.insertAudit(event), "insert AuditEvent");
        return event;
    }

    @Override
    public List<AuditLogEntity> findAdminAuditLogs(
            UUID orgId, UUID actor, String action, String resourcePrefix, int limit) {
        return mapper.findAudit(orgId, actor, action, resourcePrefix, limit);
    }

    @Override
    public UsageRecordEntity save(UsageRecordEntity record) {
        requireOne(mapper.insertUsage(record), "insert UsageRecord");
        return record;
    }

    @Override
    public long countByOrgId(UUID orgId) {
        return mapper.countUsage(orgId);
    }

    @Override
    public List<UsageAggregate> aggregateUsage(
            UUID orgId, UUID userId, String metric, OffsetDateTime from, OffsetDateTime to) {
        return mapper.aggregateUsage(orgId, userId, metric, from, to);
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
