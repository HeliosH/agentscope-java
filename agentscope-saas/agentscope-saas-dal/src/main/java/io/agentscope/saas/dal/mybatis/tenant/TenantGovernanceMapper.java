/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.AuditLogEntity;
import io.agentscope.saas.domain.model.UsageRecordEntity;
import io.agentscope.saas.domain.repository.UsageRecordRepository.UsageAggregate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Tenant mapper for immutable audit events and usage metering. */
public interface TenantGovernanceMapper {

    @Insert(
            """
            INSERT INTO audit_logs (org_id, actor, action, resource, detail)
            VALUES (#{orgId}, #{actor}, #{action}, #{resource},
                    #{detail,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAudit(AuditLogEntity event);

    @Select(
            """
            <script>
            SELECT id, org_id, actor, action, resource, detail, ts
              FROM audit_logs
             WHERE org_id = #{orgId}
             <if test="actor != null">AND actor = #{actor}</if>
             <if test="action != null">AND action = #{action}</if>
             <if test="resourcePrefix != null">
                 AND resource LIKE CONCAT(#{resourcePrefix}, '%')
             </if>
             ORDER BY ts DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<AuditLogEntity> findAudit(
            @Param("orgId") UUID orgId,
            @Param("actor") UUID actor,
            @Param("action") String action,
            @Param("resourcePrefix") String resourcePrefix,
            @Param("limit") int limit);

    @Insert(
            """
            INSERT INTO usage_records (org_id, user_id, metric, metric_value, model)
            VALUES (#{orgId}, #{userId}, #{metric}, #{value}, #{model})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUsage(UsageRecordEntity record);

    @Select("SELECT COUNT(*) FROM usage_records WHERE org_id = #{orgId}")
    long countUsage(@Param("orgId") UUID orgId);

    @Select(
            """
            <script>
            SELECT metric, model, COUNT(*) AS records,
                   COALESCE(SUM(metric_value), 0) AS total_value,
                   MIN(recorded_at) AS first_recorded_at,
                   MAX(recorded_at) AS last_recorded_at
              FROM usage_records
             WHERE org_id = #{orgId}
             <if test="userId != null">AND user_id = #{userId}</if>
             <if test="metric != null">AND metric = #{metric}</if>
             <if test="fromTs != null">AND recorded_at &gt;= #{fromTs}</if>
             <if test="toTs != null">AND recorded_at &lt;= #{toTs}</if>
             GROUP BY metric, model
             ORDER BY metric ASC, model ASC
            </script>
            """)
    List<UsageAggregate> aggregateUsage(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("metric") String metric,
            @Param("fromTs") OffsetDateTime from,
            @Param("toTs") OffsetDateTime to);
}
