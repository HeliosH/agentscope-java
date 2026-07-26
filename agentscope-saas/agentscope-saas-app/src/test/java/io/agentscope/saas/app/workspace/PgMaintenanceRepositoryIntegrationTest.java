/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.domain.sandbox.SandboxReconciliationRepository;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository.ObjectReference;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Executes administrative maintenance mappers against the configured integration database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PgMaintenanceRepositoryIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final TestDatabaseMapper database;

    @Autowired SandboxReconciliationRepository sandboxes;
    @Autowired FileObjectGcRepository files;

    @Autowired
    PgMaintenanceRepositoryIntegrationTest(
            @Qualifier("adminDataSource") DataSource adminDataSource) {
        this.database =
                MyBatisRepositoryTestSupport.mapper(adminDataSource, TestDatabaseMapper.class);
    }

    @Test
    void sandboxReleaseCanBeClaimedOnlyOnce() {
        UUID id = UUID.randomUUID();
        database.insertMaintenanceSandbox(
                id, ORG_ID, USER_ID, "maintenance-" + id, OffsetDateTime.now().minusMinutes(1));

        assertThat(sandboxes.findExpiredActive(OffsetDateTime.now(), 1000))
                .extracting(SandboxReconciliationRepository.SandboxResource::id)
                .contains(id);
        assertThat(sandboxes.markExpiredActiveEvicted(id, OffsetDateTime.now())).isEqualTo(1);
        assertThat(sandboxes.claimBackendRelease(id, 3)).isEqualTo(1);
        assertThat(sandboxes.claimBackendRelease(id, 3)).isZero();
        assertThat(sandboxes.recordBackendRelease(id, "succeeded", 1, OffsetDateTime.now(), null))
                .isEqualTo(1);
    }

    @Test
    void fileRetentionQueueAndVersionClaimsUseConditionalUpdates() {
        UUID deletedFileId = UUID.randomUUID();
        UUID deletedVersionId = UUID.randomUUID();
        insertFile(deletedFileId, "deleted", null);
        insertVersion(deletedVersionId, deletedFileId, 1, "maintenance/deleted-" + deletedFileId);
        database.updateFileTimestamp(deletedFileId, OffsetDateTime.now().minusDays(2));

        assertThat(files.findDeletedFiles(OffsetDateTime.now().minusDays(1), 1000))
                .extracting(FileObjectGcRepository.FileReference::id)
                .contains(deletedFileId);
        assertThat(files.claimDeletedFile(deletedFileId)).isEqualTo(1);
        assertThat(files.claimDeletedFile(deletedFileId)).isZero();
        ObjectReference deletedObject = files.findFileObjects(deletedFileId).get(0);
        files.enqueueObject(deletedObject, OffsetDateTime.now());
        files.deleteFileVersions(deletedFileId);
        files.deleteFile(deletedFileId);

        ObjectReference queued =
                files.findDeletionCandidates(10, 1000).stream()
                        .filter(row -> row.objectKey().equals(deletedObject.objectKey()))
                        .findFirst()
                        .orElseThrow();
        assertThat(files.claimDeletion(queued.id(), OffsetDateTime.now())).isEqualTo(1);
        assertThat(files.claimDeletion(queued.id(), OffsetDateTime.now())).isZero();
        assertThat(files.countObjectReferences(ORG_ID, queued.objectKey())).isZero();
        assertThat(files.recordDeletion(queued.id(), "succeeded", null, OffsetDateTime.now()))
                .isEqualTo(1);

        UUID activeFileId = UUID.randomUUID();
        UUID oldVersionId = UUID.randomUUID();
        UUID currentVersionId = UUID.randomUUID();
        insertFile(activeFileId, "active", null);
        insertVersion(oldVersionId, activeFileId, 1, "maintenance/old-" + activeFileId);
        insertVersion(currentVersionId, activeFileId, 2, "maintenance/current-" + activeFileId);
        database.updateCurrentFileVersion(activeFileId, currentVersionId);

        assertThat(files.findPrunableVersions(1, 1000))
                .extracting(ObjectReference::id)
                .contains(oldVersionId)
                .doesNotContain(currentVersionId);
        assertThat(files.claimPrunableVersion(oldVersionId)).isEqualTo(1);
        assertThat(files.claimPrunableVersion(currentVersionId)).isZero();
        assertThat(files.deleteFileVersion(oldVersionId)).isEqualTo(1);
    }

    private void insertFile(UUID fileId, String status, UUID currentVersionId) {
        database.insertMaintenanceFile(
                fileId, ORG_ID, USER_ID, "/maintenance/" + fileId, currentVersionId, status);
    }

    private void insertVersion(UUID versionId, UUID fileId, long versionNo, String objectKey) {
        database.insertMaintenanceVersion(
                versionId, fileId, ORG_ID, USER_ID, versionNo, objectKey, "sha-" + versionId);
    }
}
