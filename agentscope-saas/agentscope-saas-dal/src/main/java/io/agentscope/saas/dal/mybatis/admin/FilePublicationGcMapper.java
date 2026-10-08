/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.admin;

import io.agentscope.saas.dal.mybatis.tenant.FilePublicationMapper;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface FilePublicationGcMapper {
    @Select(
            "SELECT "
                    + FilePublicationMapper.COLUMNS
                    + " FROM file_publications p WHERE recoverable AND status IN ('STAGED',"
                    + " 'STORED') AND lease_until <= #{now} AND recovery_due_at <= #{now} AND"
                    + " recovery_deadline_at > #{now} AND recovery_attempts < #{maxAttempts} AND"
                    + " (status = 'STORED' OR EXISTS (SELECT 1 FROM file_publication_intents i"
                    + " WHERE i.publication_id = p.id AND NOT i.raw_write_required)) ORDER BY"
                    + " recovery_due_at, id LIMIT #{limit}")
    List<Publication> recoveryCandidates(
            @Param("now") OffsetDateTime now,
            @Param("maxAttempts") int attempts,
            @Param("limit") int limit);

    @Select(
            "SELECT "
                    + FilePublicationMapper.COLUMNS
                    + " FROM file_publications WHERE status <> 'PUBLISHED' AND eligible_at <="
                    + " #{now} AND attempts < #{maxAttempts} AND NOT (recoverable AND status IN"
                    + " ('STAGED', 'STORED') AND recovery_deadline_at > #{now}) ORDER BY"
                    + " eligible_at, id LIMIT #{limit}")
    List<Publication> candidates(
            @Param("now") OffsetDateTime now,
            @Param("maxAttempts") int attempts,
            @Param("limit") int limit);

    @Update(
            """
            UPDATE file_publications SET status = 'DELETING', reserved_bytes = 0, claim_token = #{token},
              eligible_at = #{until}, attempts = attempts + 1, updated_at = #{now}
             WHERE id = #{id} AND status <> 'PUBLISHED' AND eligible_at <= #{now} AND attempts < #{maxAttempts}
               AND NOT (recoverable AND status IN ('STAGED', 'STORED') AND recovery_deadline_at > #{now})
            """)
    int claim(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("now") OffsetDateTime now,
            @Param("until") OffsetDateTime until,
            @Param("maxAttempts") int attempts);

    @Select(
            """
            SELECT COUNT(*) FROM file_versions v JOIN file_publications p ON p.org_id = v.org_id AND p.object_key = v.object_key
             WHERE p.id = #{id}
            """)
    long references(@Param("id") UUID id);

    @Update(
            """
            UPDATE file_publications SET status = 'DELETED', claim_token = NULL, attempts = 0, eligible_at = #{recheckAt}
             WHERE id = #{id} AND status = 'DELETING' AND claim_token = #{token}
            """)
    int collected(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("recheckAt") OffsetDateTime recheckAt);

    @Update(
            """
            UPDATE file_publications SET status = 'ABORTED', claim_token = NULL, eligible_at = #{retryAt}
             WHERE id = #{id} AND status = 'DELETING' AND claim_token = #{token}
            """)
    int failed(
            @Param("id") UUID id,
            @Param("token") UUID token,
            @Param("retryAt") OffsetDateTime retryAt);
}
