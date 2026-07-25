/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.tenant;

import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant-session mapper for the relational fallback used by binary object storage. */
public interface RelationalBlobStorageMapper {

    @Update(
            """
            UPDATE ${table}
               SET org_id = #{orgId}, content_type = #{contentType}, size_bytes = #{sizeBytes},
                   sha256 = #{sha256}, data = #{data}, created_at = CURRENT_TIMESTAMP
             WHERE object_key = #{objectKey}
            """)
    int updateFileObject(
            @Param("table") String table,
            @Param("objectKey") String objectKey,
            @Param("orgId") UUID orgId,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("data") byte[] data);

    @Insert(
            """
            INSERT INTO ${table}
                (object_key, org_id, content_type, size_bytes, sha256, data, created_at)
            VALUES
                (#{objectKey}, #{orgId}, #{contentType}, #{sizeBytes}, #{sha256}, #{data},
                 CURRENT_TIMESTAMP)
            """)
    int insertFileObject(
            @Param("table") String table,
            @Param("objectKey") String objectKey,
            @Param("orgId") UUID orgId,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("data") byte[] data);

    @Select("SELECT data FROM ${table} WHERE org_id = #{orgId} AND object_key = #{objectKey}")
    BinaryBlobData findFileObject(
            @Param("table") String table,
            @Param("orgId") UUID orgId,
            @Param("objectKey") String objectKey);

    @Delete("DELETE FROM ${table} WHERE org_id = #{orgId} AND object_key = #{objectKey}")
    int deleteFileObject(
            @Param("table") String table,
            @Param("orgId") UUID orgId,
            @Param("objectKey") String objectKey);

    @Update(
            """
            UPDATE ${table}
               SET data = #{data}, created_at = CURRENT_TIMESTAMP
             WHERE snapshot_id = #{snapshotId}
            """)
    int updateSnapshot(
            @Param("table") String table,
            @Param("snapshotId") String snapshotId,
            @Param("data") byte[] data);

    @Insert(
            """
            INSERT INTO ${table} (snapshot_id, data, created_at)
            VALUES (#{snapshotId}, #{data}, CURRENT_TIMESTAMP)
            """)
    int insertSnapshot(
            @Param("table") String table,
            @Param("snapshotId") String snapshotId,
            @Param("data") byte[] data);

    @Select("SELECT data FROM ${table} WHERE snapshot_id = #{snapshotId}")
    BinaryBlobData findSnapshot(
            @Param("table") String table, @Param("snapshotId") String snapshotId);

    @Select("SELECT COUNT(*) FROM ${table} WHERE snapshot_id = #{snapshotId}")
    int countSnapshot(@Param("table") String table, @Param("snapshotId") String snapshotId);

    @Select("SELECT 1")
    int healthCheck();
}
