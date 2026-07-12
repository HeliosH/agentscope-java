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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.storage.FileObjectStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FileObjectGcJobTest {

    @Test
    void queuesMetadataDeletionBeforeRemovingUnreferencedObject() throws Exception {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:file-gc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO files (id, org_id, user_id, status, updated_at)"
                        + " VALUES (?, ?, ?, 'deleted', DATEADD('DAY', -2, CURRENT_TIMESTAMP))",
                fileId,
                orgId,
                userId);
        jdbc.update(
                "INSERT INTO file_versions"
                        + " (id, file_id, org_id, user_id, version_no, object_key, storage_backend)"
                        + " VALUES (?, ?, ?, ?, 1, 'files/object-1', 'pg')",
                versionId,
                fileId,
                orgId,
                userId);

        FileObjectStore store = mock(FileObjectStore.class);
        when(store.backend()).thenReturn("pg");
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setDeletedRetentionDays(1);
        properties.getFileStore().setGcBatchSize(10);

        FileObjectGcJob.GcSummary summary =
                new FileObjectGcJob(dataSource, provider, properties).collectOnce();

        assertThat(summary.deletedFiles()).isEqualTo(1);
        assertThat(summary.objectsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM files", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM file_object_gc_queue", String.class))
                .isEqualTo("succeeded");
        verify(store).delete(orgId, "files/object-1");
    }

    @Test
    void prunesOnlyUnattachedVersionsOutsideRetentionWindow() throws Exception {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:file-version-gc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO files"
                        + " (id, org_id, user_id, current_version_id, status, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'active', CURRENT_TIMESTAMP)",
                fileId,
                orgId,
                userId,
                v3);
        insertVersion(jdbc, v1, fileId, orgId, userId, 1, "files/v1");
        insertVersion(jdbc, v2, fileId, orgId, userId, 2, "files/v2");
        insertVersion(jdbc, v3, fileId, orgId, userId, 3, "files/v3");

        FileObjectStore store = mock(FileObjectStore.class);
        when(store.backend()).thenReturn("pg");
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setDeletedRetentionDays(30);
        properties.getFileStore().setMaxVersionsPerFile(2);

        FileObjectGcJob.GcSummary summary =
                new FileObjectGcJob(dataSource, provider, properties).collectOnce();

        assertThat(summary.prunedVersions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_versions", Long.class))
                .isEqualTo(2L);
        verify(store).delete(orgId, "files/v1");
    }

    private static void insertVersion(
            JdbcTemplate jdbc,
            UUID id,
            UUID fileId,
            UUID orgId,
            UUID userId,
            long versionNo,
            String objectKey) {
        jdbc.update(
                "INSERT INTO file_versions"
                        + " (id, file_id, org_id, user_id, version_no, object_key, storage_backend)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'pg')",
                id,
                fileId,
                orgId,
                userId,
                versionNo,
                objectKey);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute(
                "CREATE TABLE files (id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT"
                        + " NULL, current_version_id UUID, status VARCHAR(32) NOT NULL, updated_at"
                        + " TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute(
                "CREATE TABLE file_versions (id UUID PRIMARY KEY, file_id UUID NOT NULL, org_id"
                        + " UUID NOT NULL, user_id UUID NOT NULL, version_no BIGINT NOT NULL,"
                        + " object_key VARCHAR(1024) NOT NULL, storage_backend VARCHAR(32) NOT"
                        + " NULL)");
        jdbc.execute(
                "CREATE TABLE file_attachments (id UUID PRIMARY KEY, file_id UUID NOT NULL,"
                        + " file_version_id UUID NOT NULL)");
        jdbc.execute(
                "CREATE TABLE file_object_gc_queue (id UUID PRIMARY KEY, org_id UUID NOT NULL,"
                    + " object_key VARCHAR(1024) NOT NULL, storage_backend VARCHAR(32) NOT NULL,"
                    + " status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL, last_error"
                    + " VARCHAR(2000), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at"
                    + " TIMESTAMP WITH TIME ZONE NOT NULL)");
    }
}
