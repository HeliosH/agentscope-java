/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.storage;

import java.io.FileNotFoundException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL/H2 BYTEA fallback object store for local development and tests. */
public final class PgFileObjectStore implements FileObjectStore {

    private final DataSource dataSource;
    private final String table;

    public PgFileObjectStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = table != null && !table.isBlank() ? table : "file_object_blobs";
    }

    @Override
    public String backend() {
        return "pg";
    }

    @Override
    public void put(FileObject object) throws Exception {
        Objects.requireNonNull(object, "object");
        try (Connection conn = dataSource.getConnection()) {
            String updateSql =
                    "UPDATE "
                            + table
                            + " SET org_id = ?, content_type = ?, size_bytes = ?, sha256 = ?,"
                            + " data = ?, created_at = NOW() WHERE object_key = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setObject(1, object.orgId());
                ps.setString(2, object.contentType());
                ps.setLong(3, object.content() != null ? object.content().length : 0L);
                ps.setString(4, object.sha256());
                ps.setBytes(5, object.content() != null ? object.content() : new byte[0]);
                ps.setString(6, object.objectKey());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    return;
                }
            }
            String insertSql =
                    "INSERT INTO "
                            + table
                            + " (object_key, org_id, content_type, size_bytes, sha256, data,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, object.objectKey());
                ps.setObject(2, object.orgId());
                ps.setString(3, object.contentType());
                ps.setLong(4, object.content() != null ? object.content().length : 0L);
                ps.setString(5, object.sha256());
                ps.setBytes(6, object.content() != null ? object.content() : new byte[0]);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public byte[] get(UUID orgId, String objectKey) throws Exception {
        String sql = "SELECT data FROM " + table + " WHERE org_id = ? AND object_key = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, orgId);
            ps.setString(2, objectKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new FileNotFoundException("File object not found: " + objectKey);
                }
                return rs.getBytes("data");
            }
        }
    }

    @Override
    public void delete(UUID orgId, String objectKey) throws Exception {
        String sql = "DELETE FROM " + table + " WHERE org_id = ? AND object_key = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, orgId);
            ps.setString(2, objectKey);
            ps.executeUpdate();
        }
    }

    @Override
    public void healthCheck() throws Exception {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            ps.execute();
        }
    }
}
