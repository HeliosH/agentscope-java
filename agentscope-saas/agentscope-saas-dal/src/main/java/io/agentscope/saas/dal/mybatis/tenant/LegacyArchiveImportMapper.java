/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Mapping;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Owner;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Proof;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LegacyArchiveImportMapper {
    String OWNED =
            "org_id = #{owner.orgId} AND user_id = #{owner.userId} AND agent_id = #{owner.agentId}"
                    + " AND session_id = #{owner.sessionId}";
    String JOB =
            "SELECT id, org_id, user_id, agent_id, session_id, session_generation, format_version,"
                    + " source_version_id, source_sha256, source_bytes, byte_offset, line_no,"
                    + " imported_messages, skipped_entries, digest_chain, status, created_at,"
                    + " updated_at FROM legacy_archive_imports WHERE "
                    + OWNED;

    @Insert(
            """
            INSERT INTO legacy_archive_imports(id, org_id, user_id, agent_id, session_id, session_generation, format_version,
                source_version_id, source_sha256, source_bytes, byte_offset, line_no,
                imported_messages, skipped_entries, digest_chain, status, created_at, updated_at)
            VALUES(#{job.id}, #{job.orgId}, #{job.userId}, #{job.agentId}, #{job.sessionId}, #{job.sessionGeneration}, #{job.formatVersion},
                #{job.sourceVersionId}, #{job.sourceSha256}, #{job.sourceBytes}, 0, 0, 0, 0,
                #{job.digestChain}, 'READY', #{job.createdAt}, #{job.updatedAt}) ON CONFLICT DO NOTHING
            """)
    int create(@Param("job") Job job);

    @Select(JOB + " AND source_version_id = #{versionId}")
    List<Job> findBySource(@Param("owner") Owner owner, @Param("versionId") UUID versionId);

    @Select(JOB + " AND id = #{id}")
    List<Job> find(@Param("owner") Owner owner, @Param("id") UUID id);

    @Select(JOB + " AND id = #{id} FOR UPDATE")
    List<Job> lock(@Param("owner") Owner owner, @Param("id") UUID id);

    @Delete(
            "DELETE FROM legacy_archive_imports WHERE org_id = #{orgId} AND user_id = #{userId} AND"
                    + " session_id = #{sessionId}")
    int deleteSession(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId);

    @Insert(
            """
            INSERT INTO legacy_archive_entries(job_id, org_id, user_id, line_no, byte_start, byte_end,
                line_sha256, source_entry_id, parent_entry_id, tool_call_id, entry_type,
                synthetic_identity, message_id, archive_seq, content_hash)
            VALUES(#{entry.jobId}, #{entry.orgId}, #{entry.userId}, #{entry.lineNo}, #{entry.byteStart},
                #{entry.byteEnd}, #{entry.lineSha256}, #{entry.sourceEntryId}, #{entry.parentEntryId},
                #{entry.toolCallId}, #{entry.entryType}, #{entry.syntheticIdentity}, #{entry.messageId},
                #{entry.archiveSeq}, #{entry.contentHash})
            """)
    int insert(@Param("entry") Mapping entry);

    @Update(
            """
            UPDATE legacy_archive_imports SET byte_offset = #{offset}, line_no = #{lines},
                imported_messages = #{imported}, skipped_entries = #{skipped}, digest_chain = #{digest},
                status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{expected.id} AND org_id = #{expected.orgId} AND user_id = #{expected.userId}
                AND agent_id = #{expected.agentId} AND session_id = #{expected.sessionId}
                AND byte_offset = #{expected.byteOffset} AND line_no = #{expected.lineNo} AND status = 'READY'
            """)
    int advance(
            @Param("expected") Job expected,
            @Param("offset") long offset,
            @Param("lines") long lines,
            @Param("imported") long imported,
            @Param("skipped") long skipped,
            @Param("digest") String digest,
            @Param("status") String status);

    @Select(
            """
            SELECT e.job_id, e.org_id, e.user_id, e.line_no, e.byte_start, e.byte_end, e.line_sha256,
                e.source_entry_id, e.parent_entry_id, e.tool_call_id, e.entry_type,
                e.synthetic_identity, e.message_id, e.archive_seq, e.content_hash
            FROM legacy_archive_entries e JOIN legacy_archive_imports j ON j.id = e.job_id
            WHERE j.org_id = #{owner.orgId} AND j.user_id = #{owner.userId}
                AND j.agent_id = #{owner.agentId} AND j.session_id = #{owner.sessionId}
                AND e.org_id = #{owner.orgId} AND e.user_id = #{owner.userId}
                AND e.job_id = #{jobId} AND e.line_no > #{afterLine}
            ORDER BY e.line_no LIMIT #{limit}
            """)
    List<Mapping> mappings(
            @Param("owner") Owner owner,
            @Param("jobId") UUID jobId,
            @Param("afterLine") long afterLine,
            @Param("limit") int limit);

    @Select(
            """
            SELECT COUNT(*) AS entries, COUNT(e.message_id) AS converted, COALESCE(MAX(e.byte_end), 0) AS last_byte_end
            FROM legacy_archive_entries e JOIN legacy_archive_imports j ON j.id = e.job_id
            WHERE j.org_id = #{owner.orgId} AND j.user_id = #{owner.userId}
                AND j.agent_id = #{owner.agentId} AND j.session_id = #{owner.sessionId}
                AND e.org_id = #{owner.orgId} AND e.user_id = #{owner.userId} AND e.job_id = #{jobId}
            """)
    Proof proof(@Param("owner") Owner owner, @Param("jobId") UUID jobId);
}
