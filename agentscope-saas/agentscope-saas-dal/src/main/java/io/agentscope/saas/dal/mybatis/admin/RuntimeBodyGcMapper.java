/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.dal.mybatis.tenant.RuntimeBodyMapper;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository.Body;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative scans only; immutable keys and ownership are still carried on every candidate. */
public interface RuntimeBodyGcMapper {
    String ELIGIBLE =
            "eligible_at <= #{now} AND attempts < #{maxAttempts} AND NOT EXISTS (SELECT 1 FROM"
                    + " runtime_messages m WHERE m.body_id = runtime_message_bodies.id)"
                    + " AND NOT EXISTS (SELECT 1 FROM context_checkpoint_body_refs c"
                    + " WHERE c.body_id = runtime_message_bodies.id)";

    @Select(
            "SELECT "
                    + RuntimeBodyMapper.COLUMNS
                    + " FROM runtime_message_bodies WHERE "
                    + ELIGIBLE
                    + " ORDER BY eligible_at, id LIMIT #{limit}")
    List<Body> candidates(
            @Param("now") OffsetDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    @Update(
            "UPDATE runtime_message_bodies SET status = CASE WHEN status = 'DELETED' THEN 'DELETED'"
                + " ELSE 'DELETING' END, claim_token = #{token}, eligible_at = #{leaseUntil},"
                + " attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND "
                    + ELIGIBLE)
    int claim(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("maxAttempts") int maxAttempts);

    @Update(
            "UPDATE runtime_message_bodies SET status = 'DELETED', claim_token = NULL, eligible_at"
                + " = #{recheckAt}, attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}"
                + " AND status IN ('DELETING', 'DELETED') AND claim_token = #{token}")
    int collected(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("recheckAt") OffsetDateTime recheckAt);

    @Update(
            "UPDATE runtime_message_bodies SET claim_token = NULL, eligible_at = #{retryAt},"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status IN ('DELETING',"
                    + " 'DELETED') AND claim_token = #{token}")
    int failed(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("retryAt") OffsetDateTime retryAt);

    @Select(
            "SELECT (SELECT COUNT(*) FROM runtime_messages WHERE body_id = #{id}) + (SELECT"
                    + " COUNT(*) FROM context_checkpoint_body_refs WHERE body_id = #{id})")
    long references(@Param("id") UUID id);

    @Update(
            "UPDATE runtime_message_bodies SET status = 'READY', claim_token = NULL, eligible_at ="
                + " #{eligibleAt}, attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}"
                + " AND status = 'DELETING' AND claim_token = #{token}")
    int retain(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("eligibleAt") OffsetDateTime eligibleAt);
}
