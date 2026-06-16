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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * PostgreSQL-compatible {@link RemoteSnapshotClient} that stores sandbox workspace tar archives as
 * {@code BYTEA} BLOBs.
 *
 * <p>The framework ships a JDBC snapshot client in the MySQL extension, but it uses MySQL-only
 * syntax ({@code ON DUPLICATE KEY UPDATE}, {@code LONGBLOB}). This implementation uses
 * {@code INSERT ... ON CONFLICT ... DO UPDATE} and {@code BYTEA} so it works on the SaaS platform's
 * PostgreSQL datastore without introducing a separate object store. The backing table is created by
 * Flyway, not here (the app runs with {@code hibernate.ddl-auto=validate}).
 */
public class PgRemoteSnapshotClient implements RemoteSnapshotClient {

    private final DataSource dataSource;
    private final String table;

    public PgRemoteSnapshotClient(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = (table != null && !table.isBlank()) ? table : "agentscope_sandbox_snapshots";
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        String sql =
                "INSERT INTO "
                        + table
                        + " (snapshot_id, data, created_at) VALUES (?, ?, NOW()) "
                        + "ON CONFLICT (snapshot_id) DO UPDATE SET data = EXCLUDED.data, "
                        + "created_at = NOW()";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            ps.setBytes(2, bytes);
            ps.executeUpdate();
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        String sql = "SELECT data FROM " + table + " WHERE snapshot_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new FileNotFoundException("Snapshot not found: " + snapshotId);
                }
                return new ByteArrayInputStream(rs.getBytes("data"));
            }
        }
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        String sql = "SELECT 1 FROM " + table + " WHERE snapshot_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
