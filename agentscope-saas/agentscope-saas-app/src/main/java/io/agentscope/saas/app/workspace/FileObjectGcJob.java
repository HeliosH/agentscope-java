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
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.storage.FileObjectStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies file retention and retries physical deletion of objects no longer referenced by metadata. */
@Component
public class FileObjectGcJob {

    private static final Logger log = LoggerFactory.getLogger(FileObjectGcJob.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DataSource adminDataSource;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<FileObjectStore> objectStoreProvider;
    private final SaasProperties properties;

    public FileObjectGcJob(
            @Qualifier("adminDataSource") DataSource adminDataSource,
            ObjectProvider<FileObjectStore> objectStoreProvider,
            SaasProperties properties) {
        this.adminDataSource = adminDataSource;
        this.jdbc = new JdbcTemplate(adminDataSource);
        this.objectStoreProvider = objectStoreProvider;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.gc-fixed-delay-seconds:3600}",
            timeUnit = TimeUnit.SECONDS)
    public void collectScheduled() {
        SaasProperties.FileStore cfg = properties.getFileStore();
        if (!cfg.isEnabled() || !cfg.isGcEnabled()) {
            return;
        }
        try {
            GcSummary summary = collectOnce();
            if (summary.total() > 0) {
                log.info(
                        "File GC completed deletedFiles={} prunedVersions={} objectsDeleted={}"
                                + " objectsRetained={} failures={}",
                        summary.deletedFiles(),
                        summary.prunedVersions(),
                        summary.objectsDeleted(),
                        summary.objectsRetained(),
                        summary.failures());
            }
        } catch (RuntimeException e) {
            log.warn("File GC scan failed: {}", e.getMessage());
        }
    }

    GcSummary collectOnce() {
        SaasProperties.FileStore cfg = properties.getFileStore();
        int batchSize = Math.max(1, cfg.getGcBatchSize());
        int deleted = purgeDeletedFiles(cfg.getDeletedRetentionDays(), batchSize);
        int pruned = pruneOldVersions(cfg.getMaxVersionsPerFile(), batchSize);
        ObjectCounts objects = deleteQueuedObjects(batchSize, Math.max(1, cfg.getGcMaxAttempts()));
        return new GcSummary(
                deleted, pruned, objects.deleted(), objects.retained(), objects.failed());
    }

    private int purgeDeletedFiles(int retentionDays, int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(0, retentionDays));
        try (Connection connection = adminDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<FileCandidate> files = new ArrayList<>();
                try (PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT id, org_id FROM files WHERE status = 'deleted'"
                                        + " AND updated_at < ? ORDER BY updated_at ASC LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            files.add(new FileCandidate(uuid(rs, "id"), uuid(rs, "org_id")));
                        }
                    }
                }
                for (FileCandidate file : files) {
                    enqueueFileObjects(connection, file.id());
                    execute(
                            connection,
                            "DELETE FROM file_attachments WHERE file_id = ?",
                            file.id());
                    execute(connection, "DELETE FROM file_versions WHERE file_id = ?", file.id());
                    execute(connection, "DELETE FROM files WHERE id = ?", file.id());
                }
                connection.commit();
                return files.size();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to purge deleted files", e);
        }
    }

    private int pruneOldVersions(int maxVersions, int limit) {
        if (maxVersions <= 0) {
            return 0;
        }
        String sql =
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
                 WHERE version_rank > ?
                   AND id <> current_version_id
                   AND NOT EXISTS (
                       SELECT 1 FROM file_attachments a WHERE a.file_version_id = ranked.id
                   )
                 LIMIT ?
                """;
        try (Connection connection = adminDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<ObjectCandidate> versions = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, maxVersions);
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            versions.add(mapObject(rs));
                        }
                    }
                }
                for (ObjectCandidate version : versions) {
                    enqueue(connection, version);
                    execute(connection, "DELETE FROM file_versions WHERE id = ?", version.id());
                }
                connection.commit();
                return versions.size();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prune file versions", e);
        }
    }

    private ObjectCounts deleteQueuedObjects(int limit, int maxAttempts) {
        FileObjectStore store = objectStoreProvider.getIfAvailable();
        if (store == null) {
            return new ObjectCounts(0, 0, 0);
        }
        List<ObjectCandidate> candidates =
                jdbc.query(
                        "SELECT id, org_id, object_key, storage_backend"
                                + " FROM file_object_gc_queue"
                                + " WHERE status IN ('pending', 'failed') AND attempts < ?"
                                + " ORDER BY created_at ASC LIMIT ?",
                        (rs, rowNum) -> mapObject(rs),
                        maxAttempts,
                        limit);
        int deleted = 0;
        int retained = 0;
        int failed = 0;
        for (ObjectCandidate candidate : candidates) {
            if (jdbc.update(
                            "UPDATE file_object_gc_queue SET status = 'deleting',"
                                    + " attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP"
                                    + " WHERE id = ? AND status IN ('pending', 'failed')",
                            candidate.id())
                    == 0) {
                continue;
            }
            Long references =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM file_versions WHERE org_id = ? AND object_key ="
                                    + " ?",
                            Long.class,
                            candidate.orgId(),
                            candidate.objectKey());
            if (references != null && references > 0) {
                markTerminal(candidate.id(), "referenced", null);
                retained++;
                continue;
            }
            if (!store.backend().equalsIgnoreCase(candidate.storageBackend())) {
                markFailed(
                        candidate.id(),
                        "Configured backend "
                                + store.backend()
                                + " cannot delete "
                                + candidate.storageBackend()
                                + " object");
                failed++;
                continue;
            }
            try {
                withTenantOrg(
                        candidate.orgId(),
                        () -> {
                            store.delete(candidate.orgId(), candidate.objectKey());
                            return null;
                        });
                markTerminal(candidate.id(), "succeeded", null);
                deleted++;
            } catch (Exception e) {
                markFailed(candidate.id(), e.getMessage());
                failed++;
            }
        }
        return new ObjectCounts(deleted, retained, failed);
    }

    private void enqueueFileObjects(Connection connection, UUID fileId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT id, org_id, object_key, storage_backend"
                                + " FROM file_versions WHERE file_id = ?")) {
            ps.setObject(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    enqueue(connection, mapObject(rs));
                }
            }
        }
    }

    private static void enqueue(Connection connection, ObjectCandidate object) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "INSERT INTO file_object_gc_queue"
                                + " (id, org_id, object_key, storage_backend, status, attempts,"
                                + " created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, 'pending', 0, CURRENT_TIMESTAMP,"
                                + " CURRENT_TIMESTAMP)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, object.orgId());
            ps.setString(3, object.objectKey());
            ps.setString(4, object.storageBackend());
            ps.executeUpdate();
        }
    }

    private static int execute(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate();
        }
    }

    private void markTerminal(UUID id, String status, String error) {
        jdbc.update(
                "UPDATE file_object_gc_queue SET status = ?, last_error = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status,
                truncate(error),
                id);
    }

    private void markFailed(UUID id, String error) {
        markTerminal(id, "failed", error);
    }

    private static ObjectCandidate mapObject(ResultSet rs) throws SQLException {
        return new ObjectCandidate(
                uuid(rs, "id"),
                uuid(rs, "org_id"),
                rs.getString("object_key"),
                rs.getString("storage_backend"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static <T> T withTenantOrg(UUID orgId, SqlSupplier<T> supplier) throws Exception {
        String previous = TenantContextHolder.getOrgId();
        try {
            TenantContextHolder.setOrgId(orgId.toString());
            return supplier.get();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setOrgId(previous);
            }
        }
    }

    record GcSummary(
            int deletedFiles,
            int prunedVersions,
            int objectsDeleted,
            int objectsRetained,
            int failures) {
        int total() {
            return deletedFiles + prunedVersions + objectsDeleted + objectsRetained + failures;
        }
    }

    private record FileCandidate(UUID id, UUID orgId) {}

    private record ObjectCandidate(UUID id, UUID orgId, String objectKey, String storageBackend) {}

    private record ObjectCounts(int deleted, int retained, int failed) {}

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
