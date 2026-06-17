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
 * JDBC {@link RemoteSnapshotClient} that stores sandbox workspace tar archives as
 * {@code BYTEA} BLOBs. Works on PostgreSQL and H2 (PostgreSQL mode) via a portable two-step
 * upsert (update-then-insert) so that dev/local H2 verification doesn't require a separate
 * object store.
 *
 * <p>The backing table is created by Flyway, not here (the app runs with
 * {@code hibernate.ddl-auto=validate}).
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
        // Portable upsert: UPDATE first, then INSERT if no matching row existed.
        try (Connection conn = dataSource.getConnection()) {
            String updateSql =
                    "UPDATE " + table + " SET data = ?, created_at = NOW() WHERE snapshot_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setBytes(1, bytes);
                ps.setString(2, snapshotId);
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    return;
                }
            }
            String insertSql =
                    "INSERT INTO "
                            + table
                            + " (snapshot_id, data, created_at) VALUES (?, ?, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, snapshotId);
                ps.setBytes(2, bytes);
                ps.executeUpdate();
            }
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
