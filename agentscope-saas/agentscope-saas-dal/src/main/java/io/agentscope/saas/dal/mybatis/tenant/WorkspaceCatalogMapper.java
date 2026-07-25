/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.mybatis.tenant;

import io.agentscope.saas.domain.model.FileAttachmentEntity;
import io.agentscope.saas.domain.model.FileEntity;
import io.agentscope.saas.domain.model.FileVersionEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant mapper for logical workspace files, immutable versions, and attachments. */
public interface WorkspaceCatalogMapper {

    String FILE_COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, session_id, logical_path, current_version_id,
                   source, status, created_at, updated_at
              FROM files
            """;

    @Select(
            FILE_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId}
                       AND logical_path = #{logicalPath}
                    """)
    List<FileEntity> findFileByPath(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("logicalPath") String logicalPath);

    @Select(FILE_COLUMNS + " WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}")
    List<FileEntity> findOwnedFile(
            @Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            FILE_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND status = #{status}
                     ORDER BY logical_path ASC
                    """)
    List<FileEntity> findFilesByStatus(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("status") String status);

    @Select(
            FILE_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId}
                       AND logical_path = #{logicalPath}
                     FOR UPDATE
                    """)
    List<FileEntity> lockFileByPath(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("logicalPath") String logicalPath);

    @Insert(
            """
            INSERT INTO files
                (id, org_id, user_id, agent_id, session_id, logical_path, current_version_id,
                 source, status, updated_at)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{logicalPath},
                 #{currentVersionId}, #{source}, #{status}, #{updatedAt})
            """)
    int insertFile(FileEntity file);

    @Update(
            """
            UPDATE files
               SET agent_id = #{agentId}, session_id = #{sessionId},
                   logical_path = #{logicalPath}, current_version_id = #{currentVersionId},
                   source = #{source}, status = #{status}, updated_at = #{updatedAt}
             WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}
            """)
    int updateFile(FileEntity file);

    String VERSION_COLUMNS =
            """
            SELECT id, file_id, org_id, user_id, agent_id, session_id, version_no, object_key,
                   storage_backend, content_type, size_bytes, sha256, source, metadata, created_at
              FROM file_versions
            """;

    @Select(VERSION_COLUMNS + " WHERE id = #{id} AND org_id = #{orgId}")
    List<FileVersionEntity> findVersion(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Select(VERSION_COLUMNS + " WHERE id = #{id} AND org_id = #{orgId} AND user_id = #{userId}")
    List<FileVersionEntity> findOwnedVersion(
            @Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(VERSION_COLUMNS + " WHERE file_id = #{fileId} ORDER BY version_no DESC LIMIT 1")
    List<FileVersionEntity> findLatestVersion(@Param("fileId") UUID fileId);

    @Select(
            VERSION_COLUMNS
                    + """
                     WHERE file_id = #{fileId} AND org_id = #{orgId} AND user_id = #{userId}
                     ORDER BY version_no DESC
                    """)
    List<FileVersionEntity> findVersions(
            @Param("fileId") UUID fileId, @Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            """
            <script>
            """
                    + VERSION_COLUMNS
                    + """
                     WHERE id IN
                     <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                    </script>
                    """)
    List<FileVersionEntity> findVersionsByIds(@Param("ids") Collection<UUID> ids);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM file_versions WHERE file_id = #{fileId}")
    long maxVersionNo(@Param("fileId") UUID fileId);

    @Select(
            """
            SELECT COALESCE(SUM(v.size_bytes), 0)
              FROM files f JOIN file_versions v ON v.id = f.current_version_id
             WHERE f.org_id = #{orgId} AND f.user_id = #{userId} AND f.status = 'active'
            """)
    long currentUsageByUser(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Select(
            """
            SELECT COALESCE(SUM(v.size_bytes), 0)
              FROM files f JOIN file_versions v ON v.id = f.current_version_id
             WHERE f.org_id = #{orgId} AND f.status = 'active'
            """)
    long currentUsageByOrg(@Param("orgId") UUID orgId);

    @Insert(
            """
            INSERT INTO file_versions
                (id, file_id, org_id, user_id, agent_id, session_id, version_no, object_key,
                 storage_backend, content_type, size_bytes, sha256, source, metadata)
            VALUES
                (#{id}, #{fileId}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{versionNo},
                 #{objectKey}, #{storageBackend}, #{contentType}, #{sizeBytes}, #{sha256},
                 #{source},
                 #{metadata,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertVersion(FileVersionEntity version);

    String ATTACHMENT_COLUMNS =
            """
            SELECT id, org_id, user_id, agent_id, session_id, message_id, task_id, file_id,
                   file_version_id, kind, metadata, created_at
              FROM file_attachments
            """;

    @Select(
            ATTACHMENT_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND session_id = #{sessionId}
                     ORDER BY created_at DESC, id ASC
                    """)
    List<FileAttachmentEntity> findSessionAttachments(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId);

    @Select(
            ATTACHMENT_COLUMNS
                    + """
                     WHERE org_id = #{orgId} AND user_id = #{userId} AND message_id = #{messageId}
                     ORDER BY created_at DESC, id ASC
                    """)
    List<FileAttachmentEntity> findMessageAttachments(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("messageId") UUID messageId);

    @Insert(
            """
            INSERT INTO file_attachments
                (id, org_id, user_id, agent_id, session_id, message_id, task_id, file_id,
                 file_version_id, kind, metadata)
            VALUES
                (#{id}, #{orgId}, #{userId}, #{agentId}, #{sessionId}, #{messageId}, #{taskId},
                 #{fileId}, #{fileVersionId}, #{kind},
                 #{metadata,typeHandler=io.agentscope.saas.dal.mybatis.type.JsonTypeHandler})
            """)
    int insertAttachment(FileAttachmentEntity attachment);
}
