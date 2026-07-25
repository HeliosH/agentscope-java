/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Administrative MyBatis mapper for file metadata retention and object deletion queues. */
public interface FileObjectGcMapper {

    @Select(
            """
            SELECT id, org_id, NULL AS object_key, NULL AS storage_backend
              FROM files
             WHERE status = 'deleted'
               AND updated_at < #{deletedBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "object_key", javaType = String.class),
        @Arg(column = "storage_backend", javaType = String.class)
    })
    List<FileGcReferenceData> findDeletedFiles(
            @Param("deletedBefore") OffsetDateTime deletedBefore, @Param("limit") int limit);

    @Update("UPDATE files SET status = 'purging' WHERE id = #{fileId} AND status = 'deleted'")
    int claimDeletedFile(@Param("fileId") UUID fileId);

    @Select(
            """
            SELECT id, org_id, object_key, storage_backend
              FROM file_versions
             WHERE file_id = #{fileId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "object_key", javaType = String.class),
        @Arg(column = "storage_backend", javaType = String.class)
    })
    List<FileGcReferenceData> findFileObjects(@Param("fileId") UUID fileId);

    @Insert(
            """
            INSERT INTO file_object_gc_queue
                (id, org_id, object_key, storage_backend, status, attempts, created_at, updated_at)
            VALUES
                (#{id}, #{orgId}, #{objectKey}, #{storageBackend}, 'pending', 0,
                 #{createdAt}, #{createdAt})
            """)
    int enqueueObject(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("objectKey") String objectKey,
            @Param("storageBackend") String storageBackend,
            @Param("createdAt") OffsetDateTime createdAt);

    @Delete("DELETE FROM file_attachments WHERE file_id = #{fileId}")
    int deleteFileAttachments(@Param("fileId") UUID fileId);

    @Delete("DELETE FROM file_versions WHERE file_id = #{fileId}")
    int deleteFileVersions(@Param("fileId") UUID fileId);

    @Delete("DELETE FROM files WHERE id = #{fileId}")
    int deleteFile(@Param("fileId") UUID fileId);

    @Select(
            """
            SELECT id, org_id, object_key, storage_backend
              FROM (
                    SELECT v.id, v.org_id, v.object_key, v.storage_backend,
                           f.current_version_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY v.file_id ORDER BY v.version_no DESC
                           ) AS version_rank
                      FROM file_versions v
                      JOIN files f ON f.id = v.file_id
                     WHERE f.status = 'active'
                   ) ranked
             WHERE version_rank > #{maxVersions}
               AND id <> current_version_id
               AND NOT EXISTS (
                   SELECT 1 FROM file_attachments a WHERE a.file_version_id = ranked.id
               )
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "object_key", javaType = String.class),
        @Arg(column = "storage_backend", javaType = String.class)
    })
    List<FileGcReferenceData> findPrunableVersions(
            @Param("maxVersions") int maxVersions, @Param("limit") int limit);

    @Update(
            """
            UPDATE file_versions candidate
               SET version_no = version_no
             WHERE candidate.id = #{versionId}
               AND NOT EXISTS (
                   SELECT 1 FROM files f WHERE f.current_version_id = candidate.id
               )
               AND NOT EXISTS (
                   SELECT 1 FROM file_attachments a
                    WHERE a.file_version_id = candidate.id
               )
            """)
    int claimPrunableVersion(@Param("versionId") UUID versionId);

    @Delete("DELETE FROM file_versions WHERE id = #{versionId}")
    int deleteFileVersion(@Param("versionId") UUID versionId);

    @Select(
            """
            SELECT id, org_id, object_key, storage_backend
              FROM file_object_gc_queue
             WHERE status IN ('pending', 'failed')
               AND attempts < #{maxAttempts}
             ORDER BY created_at ASC
             LIMIT #{limit}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class),
        @Arg(column = "org_id", javaType = UUID.class),
        @Arg(column = "object_key", javaType = String.class),
        @Arg(column = "storage_backend", javaType = String.class)
    })
    List<FileGcReferenceData> findDeletionCandidates(
            @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Update(
            """
            UPDATE file_object_gc_queue
               SET status = 'deleting', attempts = attempts + 1, updated_at = #{changedAt}
             WHERE id = #{queueId} AND status IN ('pending', 'failed')
            """)
    int claimDeletion(@Param("queueId") UUID queueId, @Param("changedAt") OffsetDateTime changedAt);

    @Select(
            """
            SELECT COUNT(*)
              FROM file_versions
             WHERE org_id = #{orgId} AND object_key = #{objectKey}
            """)
    long countObjectReferences(@Param("orgId") UUID orgId, @Param("objectKey") String objectKey);

    @Update(
            """
            UPDATE file_object_gc_queue
               SET status = #{status}, last_error = #{error}, updated_at = #{changedAt}
             WHERE id = #{queueId}
            """)
    int recordDeletion(
            @Param("queueId") UUID queueId,
            @Param("status") String status,
            @Param("error") String error,
            @Param("changedAt") OffsetDateTime changedAt);
}
