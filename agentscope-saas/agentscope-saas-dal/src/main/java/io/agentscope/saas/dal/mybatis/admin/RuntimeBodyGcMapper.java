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
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative scans only; immutable keys and ownership are still carried on every candidate. */
public interface RuntimeBodyGcMapper {
    String ELIGIBLE =
            "eligible_at <= #{now} AND attempts < #{maxAttempts} AND NOT EXISTS (SELECT 1 FROM"
                + " runtime_messages m WHERE m.body_id = runtime_message_bodies.id) AND NOT EXISTS"
                + " (SELECT 1 FROM context_checkpoint_body_refs c WHERE c.body_id ="
                + " runtime_message_bodies.id) AND NOT EXISTS (SELECT 1 FROM workspace_archive_jobs"
                + " j WHERE j.body_id = runtime_message_bodies.id AND j.status IN ('PENDING',"
                + " 'RUNNING') AND j.deadline_at > #{now}) AND NOT EXISTS (SELECT 1 FROM"
                + " runtime_body_fallbacks f WHERE f.body_id = runtime_message_bodies.id AND"
                + " f.lease_until > #{now})";

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
                    + " COUNT(*) FROM context_checkpoint_body_refs WHERE body_id = #{id}) + (SELECT"
                    + " COUNT(*) FROM workspace_archive_jobs WHERE body_id = #{id} AND status IN"
                    + " ('PENDING', 'RUNNING') AND deadline_at > CURRENT_TIMESTAMP)")
    long references(@Param("id") UUID id);

    @Update(
            "UPDATE runtime_message_bodies SET status = 'READY', claim_token = NULL, eligible_at ="
                + " #{eligibleAt}, attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}"
                + " AND status = 'DELETING' AND claim_token = #{token}")
    int retain(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("eligibleAt") OffsetDateTime eligibleAt);

    @Delete(
            "DELETE FROM runtime_body_fallbacks WHERE body_id = #{id} AND EXISTS (SELECT 1 FROM"
                    + " runtime_message_bodies b WHERE b.id = #{id} AND b.status = 'DELETED')")
    int purgeFallback(@Param("id") UUID id);

    @Select(
            """
            SELECT b.id, b.org_id, b.user_id, b.agent_id, b.session_id, b.object_key, b.backend,
                b.sha256, b.size_bytes, b.status, b.eligible_at, b.claim_token, b.attempts
            FROM runtime_body_fallbacks f JOIN runtime_message_bodies b ON b.id = f.body_id
            WHERE b.status = 'READY' AND f.next_attempt_at <= #{now}
                AND (f.lease_until IS NULL OR f.lease_until <= #{now})
            ORDER BY f.next_attempt_at, f.body_id LIMIT #{limit}
            """)
    List<Body> fallbackCandidates(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    // Recovery claims and GC lock the body before touching its fallback row.
    @Update(
            "UPDATE runtime_message_bodies SET eligible_at = CASE WHEN eligible_at > #{leaseUntil}"
                + " THEN eligible_at ELSE #{leaseUntil} END WHERE id = #{id} AND status = 'READY'"
                + " AND EXISTS (SELECT 1 FROM runtime_body_fallbacks f WHERE f.body_id = #{id} AND"
                + " f.next_attempt_at <= #{now} AND (f.lease_until IS NULL OR f.lease_until <="
                + " #{now}))")
    int protectFallback(
            @Param("id") UUID id,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update(
            "UPDATE runtime_body_fallbacks SET claim_token = #{token}, lease_until = #{leaseUntil},"
                + " next_attempt_at = #{leaseUntil} WHERE body_id = #{id} AND next_attempt_at <="
                + " #{now} AND (lease_until IS NULL OR lease_until <= #{now})")
    int claimFallback(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Delete(
            "DELETE FROM runtime_body_fallbacks WHERE body_id = #{id} AND claim_token = #{token}"
                    + " AND lease_until > #{now} AND EXISTS (SELECT 1 FROM runtime_message_bodies b"
                    + " WHERE b.id = #{id} AND b.status = 'READY')")
    int offloaded(
            @Param("id") UUID id, @Param("token") UUID token, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE runtime_body_fallbacks SET claim_token = NULL, lease_until = NULL,"
                + " next_attempt_at = #{retryAt} WHERE body_id = #{id} AND claim_token = #{token}")
    int retryFallback(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("retryAt") OffsetDateTime retryAt);
}
