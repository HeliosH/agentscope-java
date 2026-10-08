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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper;
import io.agentscope.saas.dal.mybatis.tenant.FilePublicationMapper;
import io.agentscope.saas.dal.repository.MyBatisFileObjectGcRepository;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class FileObjectGcJobTest {
    private final List<SingleConnectionDataSource> sources = new ArrayList<>();

    @AfterEach
    void closeSources() {
        sources.forEach(SingleConnectionDataSource::destroy);
    }

    @Test
    void temporaryPublicationPinIsRetriedAfterAbortInsteadOfBecomingTerminal() throws Exception {
        DataSource ds = dataSource("publication-pin-gc");
        TestDatabaseMapper database =
                MyBatisRepositoryTestSupport.mapper(ds, TestDatabaseMapper.class);
        database.createFileGcSchema();
        publicationSchema(ds);
        UUID org = UUID.randomUUID(),
                user = UUID.randomUUID(),
                file = UUID.randomUUID(),
                publication = UUID.randomUUID();
        database.insertGcFile(file, org, user, null, "deleted", OffsetDateTime.now().minusDays(2));
        database.insertGcVersion(UUID.randomUUID(), file, org, user, 1, "files/pinned");
        var reservations = MyBatisRepositoryTestSupport.mapper(ds, FilePublicationMapper.class);
        OffsetDateTime until = OffsetDateTime.now().plusHours(1);
        reservations.stage(
                new Publication(
                        publication,
                        org,
                        user,
                        null,
                        null,
                        null,
                        null,
                        "outputs/pinned.txt",
                        "files/pinned",
                        "pg",
                        "0".repeat(64),
                        3,
                        3,
                        false,
                        "STAGED",
                        until,
                        until,
                        null,
                        0,
                        null));
        FileObjectStore store = mock(FileObjectStore.class);
        when(store.backend()).thenReturn("pg");
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        SaasProperties cfg = new SaasProperties();
        cfg.getFileStore().setDeletedRetentionDays(1);
        var gc = job(ds, provider, cfg);
        assertThat(gc.collectOnce().objectsRetained()).isEqualTo(1);
        assertThat(database.gcQueueStatus()).isEqualTo("pending");
        verify(store, never()).delete(org, "files/pinned");
        reservations.abort(org, user, publication, OffsetDateTime.now());
        assertThat(gc.collectOnce().objectsDeleted()).isEqualTo(1);
        verify(store).delete(org, "files/pinned");
    }

    @Test
    void queuesMetadataDeletionBeforeRemovingUnreferencedObject() throws Exception {
        DataSource dataSource = dataSource("file-gc");
        TestDatabaseMapper database =
                MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createFileGcSchema();
        publicationSchema(dataSource);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        database.insertGcFile(
                fileId, orgId, userId, null, "deleted", OffsetDateTime.now().minusDays(2));
        database.insertGcVersion(versionId, fileId, orgId, userId, 1, "files/object-1");

        FileObjectStore store = mock(FileObjectStore.class);
        when(store.backend()).thenReturn("pg");
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setDeletedRetentionDays(1);
        properties.getFileStore().setGcBatchSize(10);

        FileObjectGcJob.GcSummary summary = job(dataSource, provider, properties).collectOnce();

        assertThat(summary.deletedFiles()).isEqualTo(1);
        assertThat(summary.objectsDeleted()).isEqualTo(1);
        assertThat(database.countFiles()).isZero();
        assertThat(database.gcQueueStatus()).isEqualTo("succeeded");
        verify(store).delete(orgId, "files/object-1");
    }

    @Test
    void prunesOnlyUnattachedVersionsOutsideRetentionWindow() throws Exception {
        DataSource dataSource = dataSource("file-version-gc");
        TestDatabaseMapper database =
                MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        database.createFileGcSchema();
        publicationSchema(dataSource);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        database.insertGcFile(fileId, orgId, userId, v3, "active", OffsetDateTime.now());
        database.insertGcVersion(v1, fileId, orgId, userId, 1, "files/v1");
        database.insertGcVersion(v2, fileId, orgId, userId, 2, "files/v2");
        database.insertGcVersion(v3, fileId, orgId, userId, 3, "files/v3");

        FileObjectStore store = mock(FileObjectStore.class);
        when(store.backend()).thenReturn("pg");
        @SuppressWarnings("unchecked")
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        SaasProperties properties = new SaasProperties();
        properties.getFileStore().setDeletedRetentionDays(30);
        properties.getFileStore().setMaxVersionsPerFile(2);

        FileObjectGcJob.GcSummary summary = job(dataSource, provider, properties).collectOnce();

        assertThat(summary.prunedVersions()).isEqualTo(1);
        assertThat(database.countFileVersions()).isEqualTo(2L);
        verify(store).delete(orgId, "files/v1");
    }

    private static FileObjectGcJob job(
            DataSource dataSource,
            ObjectProvider<FileObjectStore> provider,
            SaasProperties properties) {
        FileObjectGcRepository repository =
                new MyBatisFileObjectGcRepository(
                        MyBatisRepositoryTestSupport.mapper(dataSource, FileObjectGcMapper.class));
        return new FileObjectGcJob(
                repository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                provider,
                properties);
    }

    private static void publicationSchema(DataSource dataSource) {
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/h2/V41__file_publication_reservations.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V42__file_publication_recovery_intents.sql"))
                .execute(dataSource);
    }

    private DataSource dataSource(String name) throws java.sql.SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:"
                        + name
                        + "-"
                        + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        // H2's optimized CHECK IN comparator retains its schema-creating session.
        var shared = new SingleConnectionDataSource(dataSource.getConnection(), true);
        sources.add(shared);
        return shared;
    }
}
