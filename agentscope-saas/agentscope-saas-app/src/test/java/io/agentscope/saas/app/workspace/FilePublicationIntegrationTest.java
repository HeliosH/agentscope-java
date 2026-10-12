/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionReport;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.memory.RuntimeBodyService;
import io.agentscope.saas.app.orchestration.DurableTaskLeaseService;
import io.agentscope.saas.app.orchestration.WorkspaceArtifactService;
import io.agentscope.saas.app.orchestration.WorkspaceCheckpointRestoreService;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.NewRunArtifact;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.SessionFence;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import io.agentscope.saas.orchestration.RunOrchestrationService;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "saas.file-store.gc-enabled=false",
            "saas.runtime-archive.body-gc-enabled=false",
            "saas.file-store.workspace-archive-enabled=false",
            "saas.file-store.workspace-archive-fixed-delay-seconds=86400",
            "saas.file-store.workspace-source-gc-fixed-delay-seconds=86400",
            "saas.file-store.publication-recovery-enabled=false"
        })
@ActiveProfiles("local")
class FilePublicationIntegrationTest {
    @Autowired FileRepository files;
    @Autowired FileVersionRepository versions;
    @Autowired FileAttachmentRepository attachments;
    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;
    @Autowired FilePublicationRepository publications;
    @Autowired RunOrchestrationRepository runs;
    @Autowired RunArtifactRepository artifacts;
    @Autowired WorkspaceArtifactService workspaceArtifacts;
    @Autowired WorkspaceManifestService manifestService;
    @Autowired WorkspaceManifestRepository manifestRepository;
    @Autowired io.agentscope.saas.domain.workspace.WorkspaceSourceRepository sourceRepository;
    @Autowired io.agentscope.saas.domain.workspace.WorkspaceInputRepository inputRepository;
    @Autowired io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository retentionRepository;
    @Autowired ChatPersistenceService chats;
    @Autowired RunOrchestrationService orchestration;
    @Autowired ObjectMapper mapper;
    @Autowired WorkspaceArchiveRepository archives;
    @Autowired RuntimeBodyRepository runtimeBodies;
    @Autowired RuntimeMessageRepository runtimeMessages;
    @Autowired DurableTaskLeaseService durableLeases;

    @Autowired
    io.agentscope.saas.domain.orchestration.DurableTaskLeaseRepository taskLeaseRepository;

    @Autowired io.agentscope.saas.domain.sandbox.SandboxLeaseRepository sandboxLeases;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    @Autowired
    @Qualifier("adminTransactionOperations")
    TransactionOperations admin;

    UUID org, user, agent, session;
    TenantContext tenant;
    SaasProperties properties;
    RecordingStore objects;
    ObjectProvider<FileObjectStore> stores;
    FileCatalogService catalog;
    FilePublicationGcJob gc;
    ExecutorService executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        var db = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        db.insertInvocationOrg(org, "publication-" + org);
        db.insertInvocationUser(user, org, user + "@publication.test");
        db.insertRuntimeArchiveAgent(agent, org, user);
        db.insertRuntimeArchiveSession(session, org, user, agent);
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        properties = new SaasProperties();
        properties.getFileStore().setMaxUserBytes(10);
        properties.getFileStore().setMaxOrgBytes(100);
        properties.getFileStore().setPublicationRecoveryEnabled(false);
        objects = new RecordingStore();
        stores = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(objects);
        catalog = catalog(versions);
        gc = new FilePublicationGcJob(publications, admin, stores, properties);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void close() throws Exception {
        objects.release.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        TenantContextHolder.clear();
    }

    @Test
    void publishesVerifiedObjectAndQuotaReceiptAtomically() {
        var file = write("outputs/a.txt", "1234567890");
        Publication p = publication(file.objectKey());
        assertThat(p.status()).isEqualTo("PUBLISHED");
        assertThat(p.reservedBytes()).isZero();
        assertThat(p.versionId()).isEqualTo(file.versionId());
        assertThat(catalog.readCurrentFile(tenant, "outputs/a.txt").orElseThrow().text())
                .isEqualTo("1234567890");
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
    }

    @Test
    void reducingWritePolicyDoesNotMakeAnAuthorizedImmutableVersionUndownloadable()
            throws Exception {
        properties.getFileStore().setMaxFileBytes(5);
        var file = write("outputs/historical.txt", "12345");
        properties.getFileStore().setMaxFileBytes(4);
        var descriptor = catalog.describeVersion(tenant, file.versionId()).orElseThrow();
        assertThat(descriptor.sizeBytes()).isEqualTo(5);
        try (var input = descriptor.source().open()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes("12345"));
        }
        assertThatThrownBy(() -> write("outputs/new.txt", "12345"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void identicalActiveVersionDoesNotUploadOrCreateAnotherVersion() {
        var first = write("outputs/a.txt", "abc");
        var second = write("outputs/a.txt", "abc");
        assertThat(second.versionId()).isEqualTo(first.versionId());
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(catalog.listVersions(tenant, "outputs/a.txt")).hasSize(1);
        gc.collectOnce(OffsetDateTime.now().plusDays(2));
        assertThat(objects.data).containsKey(first.objectKey());
    }

    @Test
    void slowPutDoesNotHoldQuotaLocksAndConcurrentReservationCannotOverbook() throws Exception {
        objects.blockFirst = true;
        Future<?> slow = async(() -> write("outputs/slow.txt", "12345678"));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isEqualTo(8);
        Future<?> rejected = async(() -> write("outputs/other.txt", "123"));
        assertThatThrownBy(() -> rejected.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage(
                        "507 INSUFFICIENT_STORAGE \"User file quota exceeded: 11 > 10\"");
        // A different path fitting the remaining reservation must complete before the blocked PUT.
        Future<?> allowed = async(() -> write("outputs/two.txt", "12"));
        allowed.get(3, TimeUnit.SECONDS);
        objects.release.countDown();
        slow.get(3, TimeUnit.SECONDS);
        assertThat(catalog.currentUsage(tenant).userBytes()).isEqualTo(10);
    }

    @Test
    void samePathDeleteAndMoveCannotRacePendingPublication() throws Exception {
        objects.blockFirst = true;
        Future<?> slow = async(() -> write("outputs/a.txt", "abc"));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> write("outputs/a.txt", "def")).hasMessageContaining("409");
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                tx -> catalog.markDeleted(tenant, "outputs/a.txt")))
                .hasMessageContaining("409");
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                tx ->
                                                        catalog.moveFile(
                                                                tenant,
                                                                agent,
                                                                null,
                                                                "outputs/a.txt",
                                                                "outputs/b.txt")))
                .hasMessageContaining("409");
        objects.release.countDown();
        slow.get(3, TimeUnit.SECONDS);
        assertThat(catalog.readCurrentFile(tenant, "outputs/a.txt").orElseThrow().text())
                .isEqualTo("abc");
    }

    @Test
    void catalogFailureRollsBackMetadataAndGcCollectsLateOrphanAgain() {
        FileVersionRepository failed = mock(FileVersionRepository.class, delegatesTo(versions));
        doThrow(new IllegalStateException("catalog unavailable")).when(failed).save(any());
        assertThatThrownBy(
                        () ->
                                catalog(failed)
                                        .recordWorkspaceFile(
                                                tenant,
                                                agent,
                                                null,
                                                "outputs/a.txt",
                                                bytes("abc"),
                                                "text/plain",
                                                "test",
                                                Map.of()))
                .hasMessageContaining("catalog unavailable");
        String key = objects.lastKey;
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(files.findByOrgIdAndUserIdAndLogicalPath(org, user, "outputs/a.txt")).isEmpty();
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
        gc.collectOnce(OffsetDateTime.now().plusHours(1));
        assertThat(objects.data).doesNotContainKey(key);
        assertThat(publication(key).status()).isEqualTo("DELETED");
        objects.data.put(key, bytes("late server PUT"));
        gc.collectOnce(OffsetDateTime.now().plusDays(2));
        assertThat(objects.data).doesNotContainKey(key);
    }

    @Test
    void ambiguousPutFailureStillHasDurableDeletionOwnership() {
        objects.failPut = true;
        assertThatThrownBy(() -> write("outputs/a.txt", "abc"))
                .hasMessageContaining("Failed to store");
        String key = objects.lastKey;
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(objects.data).containsKey(key);
        gc.collectOnce(OffsetDateTime.now().plusHours(1));
        assertThat(objects.data).doesNotContainKey(key);
    }

    @Test
    void corruptReadBackNeverCreatesCatalogVersion() {
        objects.corrupt = true;
        assertThatThrownBy(() -> write("outputs/a.txt", "abc"))
                .hasMessageContaining("INTEGRITY_FAILED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(publication(objects.lastKey).status()).isEqualTo("ABORTED");
    }

    @Test
    void expiredWriterCannotPublishAndNewWriterUsesDifferentObject() throws Exception {
        objects.blockFirst = true;
        Future<?> slow = async(() -> write("outputs/a.txt", "abc"));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        String oldKey = objects.lastKey;
        var db = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        db.expireFilePublication(id(oldKey), OffsetDateTime.now().minusMinutes(1));
        var fresh = write("outputs/a.txt", "def");
        assertThat(fresh.objectKey()).isNotEqualTo(oldKey);
        gc.collectOnce(OffsetDateTime.now().plusHours(1));
        assertThat(publication(oldKey).status()).isEqualTo("DELETED");
        objects.release.countDown();
        assertThatThrownBy(() -> slow.get(3, TimeUnit.SECONDS))
                .hasRootCauseMessage("FILE_PUBLICATION_EXPIRED");
        assertThat(objects.data).containsKey(oldKey);
        gc.collectOnce(OffsetDateTime.now().plusDays(2));
        assertThat(objects.data).containsKey(fresh.objectKey()).doesNotContainKey(oldKey);
        assertThat(catalog.readCurrentFile(tenant, "outputs/a.txt").orElseThrow().text())
                .isEqualTo("def");
    }

    @Test
    void resetRevokesReservationWithoutWaitingForIoAndNewGenerationCanPublish() throws Exception {
        objects.blockFirst = true;
        var oldFence = runs.findSessionFence(session, org, user, agent).orElseThrow();
        Future<?> slow =
                async(
                        () ->
                                catalog.recordWorkspaceFileForExecution(
                                        tenant,
                                        agent,
                                        null,
                                        oldFence,
                                        "outputs/a.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "test",
                                        Map.of()));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        String oldKey = objects.lastKey;
        Future<?> reset = async(() -> chats.resetSession(session));
        reset.get(3, TimeUnit.SECONDS);
        assertThat(publication(oldKey).status()).isEqualTo("ABORTED");
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
        var freshFence = runs.findSessionFence(session, org, user, agent).orElseThrow();
        assertThat(freshFence.generation()).isEqualTo(1);
        var fresh =
                catalog.recordWorkspaceFileForExecution(
                                tenant,
                                agent,
                                null,
                                freshFence,
                                "outputs/a.txt",
                                bytes("new"),
                                "text/plain",
                                "test",
                                Map.of())
                        .orElseThrow();
        objects.release.countDown();
        assertThatThrownBy(() -> slow.get(3, TimeUnit.SECONDS))
                .hasRootCauseMessage("FILE_PUBLICATION_EXPIRED");
        gc.collectOnce(OffsetDateTime.now().plusHours(1));
        assertThat(objects.data).containsKey(fresh.objectKey()).doesNotContainKey(oldKey);
    }

    @Test
    void workspaceMutationIsOutsideTransactionAndFailureDoesNotPublish() {
        assertThatThrownBy(
                        () ->
                                catalog.recordWorkspaceFileWithWrite(
                                        tenant,
                                        agent,
                                        null,
                                        "outputs/a.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "test",
                                        Map.of(),
                                        () -> {
                                            assertThat(
                                                            TransactionSynchronizationManager
                                                                    .isActualTransactionActive())
                                                    .isFalse();
                                            throw new IllegalStateException(
                                                    "workspace write failed");
                                        }))
                .hasMessageContaining("workspace write failed");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(publication(objects.lastKey).status()).isEqualTo("ABORTED");
    }

    @Test
    void sessionBoundUserUploadCanFinishAfterResetWithoutChangingChatHistory() throws Exception {
        objects.blockFirst = true;
        Future<?> upload =
                async(
                        () ->
                                catalog.recordWorkspaceFile(
                                        tenant,
                                        agent,
                                        session,
                                        "inputs/a.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "workspace_upload",
                                        Map.of()));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        String key = objects.lastKey;
        Future<?> reset = async(() -> chats.resetSession(session));
        reset.get(3, TimeUnit.SECONDS);
        assertThat(publication(key).status()).isEqualTo("STAGED");
        assertThat(publication(key).sessionGeneration()).isNull();
        objects.release.countDown();
        upload.get(3, TimeUnit.SECONDS);
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
        assertThat(catalog.readCurrentFile(tenant, "inputs/a.txt").orElseThrow().text())
                .isEqualTo("abc");
        assertThat(runs.findSessionFence(session, org, user, agent).orElseThrow().generation())
                .isEqualTo(1);
    }

    @Test
    void sessionDeletionRejectsLateUserUploadWithoutRecreatingTheSession() throws Exception {
        objects.blockFirst = true;
        Future<?> upload =
                async(
                        () ->
                                catalog.recordWorkspaceFile(
                                        tenant,
                                        agent,
                                        session,
                                        "inputs/a.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "workspace_upload",
                                        Map.of()));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        String key = objects.lastKey;
        Future<?> deletion = async(() -> chats.deleteSession(session));
        deletion.get(3, TimeUnit.SECONDS);
        objects.release.countDown();
        assertThatThrownBy(() -> upload.get(3, TimeUnit.SECONDS))
                .hasRootCauseMessage("SESSION_EXECUTION_REVOKED");
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(runs.findSessionFence(session, org, user, agent)).isEmpty();
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        gc.collectOnce(OffsetDateTime.now().plusHours(1));
        assertThat(objects.data).doesNotContainKey(key);
    }

    @Test
    void callerTransactionIsRejectedBeforeAnyTransfer() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(tx -> write("outputs/a.txt", "abc")))
                .hasMessageContaining("outside a transaction");
        assertThat(objects.puts.get()).isZero();
    }

    @Test
    void reservationReadsAreTenantAndEmployeeScoped() {
        var file = write("outputs/a.txt", "abc");
        java.util.Optional<Publication> foreignUser =
                new TransactionTemplate(manager)
                        .execute(
                                tx ->
                                        publications.lockOwned(
                                                org, UUID.randomUUID(), id(file.objectKey())));
        java.util.Optional<Publication> foreignOrg =
                new TransactionTemplate(manager)
                        .execute(
                                tx ->
                                        publications.lockOwned(
                                                UUID.randomUUID(), user, id(file.objectKey())));
        assertThat(foreignUser).isEmpty();
        assertThat(foreignOrg).isEmpty();
    }

    @Test
    void sqlFailureAfterStoredBytesIsRecoveredOnceByNewCoordinator() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(false);
        assertThat(publication(key).status()).isEqualTo("STORED");
        due(key);
        FilePublicationRecoveryJob job =
                new FilePublicationRecoveryJob(
                        publications, catalog(versions), properties, manager);
        assertThat(job.recoverOnce()).isEqualTo(1);
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
        assertThat(publications.intent(org, user, id(key)).orElseThrow().contentType())
                .isEqualTo("text/plain");
        assertThat(catalog.listVersions(tenant, "outputs/recover.txt")).hasSize(1);
        assertThat(job.recoverOnce()).isZero();
        assertThat(objects.puts.get()).isEqualTo(1);
    }

    @Test
    void disabledRecoveryReleasesFailedPublicationReservation() {
        String key = failedSqlPublication(false);
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
    }

    @Test
    void recoveryDoesNotRepeatCommittedWorkspaceMutation() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(true);
        due(key);
        assertThat(
                        new FilePublicationRecoveryJob(
                                        publications, catalog(versions), properties, manager)
                                .recoverOnce())
                .isEqualTo(1);
        assertThat(catalog.readCurrentFile(tenant, "outputs/recover.txt").orElseThrow().text())
                .isEqualTo("abc");
        assertThat(objects.puts.get()).isEqualTo(1);
    }

    @Test
    void recoveryCannotOverwriteANewerVersion() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(false);
        due(key);
        var newer = write("outputs/recover.txt", "new");
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isZero();
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(catalog.readCurrentFile(tenant, "outputs/recover.txt").orElseThrow().text())
                .isEqualTo("new");
        assertThat(publication(newer.objectKey()).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void repeatedStorageFaultsExhaustBoundedRecoveryAndBecomeGcEligible() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        properties.getFileStore().setPublicationRecoveryMaxAttempts(2);
        String key = failedSqlPublication(false);
        objects.data.remove(key);
        var job = new FilePublicationRecoveryJob(publications, catalog, properties, manager);
        due(key);
        assertThat(job.recoverOnce()).isZero();
        assertThat(publication(key).status()).isEqualTo("STORED");
        due(key);
        assertThat(job.recoverOnce()).isZero();
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(publications.recovery(org, user, id(key)).orElseThrow().recoveryAttempts())
                .isEqualTo(2);
    }

    @Test
    void corruptStoredObjectIsRejectedRatherThanPublished() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(false);
        objects.data.put(key, bytes("bad"));
        due(key);
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isZero();
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
    }

    @Test
    void gcCannotClaimARecoverableObjectBeforeRecoveryDeadline() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(false);
        due(key);
        assertThat(
                        publications.claim(
                                id(key),
                                UUID.randomUUID(),
                                OffsetDateTime.now(),
                                OffsetDateTime.now().plusMinutes(1),
                                10))
                .isZero();
        assertThat(objects.data).containsKey(key);
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isEqualTo(1);
    }

    @Test
    void olderRecoveryTokenAndForegroundWriterCannotSettleAnotherClaim() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String key = failedSqlPublication(false);
        due(key);
        UUID token = UUID.randomUUID();
        assertThat(
                        publications.reclaimRecovery(
                                org,
                                user,
                                id(key),
                                token,
                                OffsetDateTime.now(),
                                OffsetDateTime.now().plusMinutes(1),
                                3))
                .isEqualTo(1);
        assertThat(publications.stored(org, user, id(key), OffsetDateTime.now())).isZero();
        assertThat(publications.abort(org, user, id(key), OffsetDateTime.now())).isZero();
        assertThat(
                        publications.abandonRecovery(
                                org, user, id(key), UUID.randomUUID(), OffsetDateTime.now()))
                .isZero();
        assertThat(
                        publications.publishRecovered(
                                org,
                                user,
                                id(key),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                OffsetDateTime.now()))
                .isZero();
        assertThat(publications.abandonRecovery(org, user, id(key), token, OffsetDateTime.now()))
                .isEqualTo(1);
    }

    @Test
    void foregroundAttemptPublicationRejectsForeignOwnerAndCancelledRun() {
        var run =
                orchestration.createDirectRun(
                        tenant,
                        agent,
                        session,
                        null,
                        "publication",
                        "publication-" + UUID.randomUUID());
        MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class)
                .activateAttemptLease(
                        run.rootAttemptId(),
                        "publication-owner",
                        OffsetDateTime.now().plusHours(1),
                        OffsetDateTime.now());
        var scope =
                new Execution(
                        run.rootTaskId(),
                        run.rootAgentRunId(),
                        run.rootAttemptId(),
                        "publication-owner");
        var fence = runs.findCurrentSessionFence(run.runId(), org, user, agent).orElseThrow();
        assertThatThrownBy(
                        () ->
                                catalog.recordWorkspaceFileForAttempt(
                                        tenant,
                                        agent,
                                        run.runId(),
                                        fence,
                                        new Execution(
                                                scope.taskId(),
                                                scope.agentRunId(),
                                                scope.attemptId(),
                                                "foreign-owner"),
                                        "outputs/task.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "test",
                                        Map.of()))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
        assertThat(objects.puts.get()).isZero();
        catalog.recordWorkspaceFileForAttempt(
                        tenant,
                        agent,
                        run.runId(),
                        fence,
                        scope,
                        "outputs/task.txt",
                        bytes("abc"),
                        "text/plain",
                        "test",
                        Map.of())
                .orElseThrow();
        orchestration.cancel(tenant, agent, run.runId());
        assertThatThrownBy(
                        () ->
                                catalog.recordWorkspaceFileForAttempt(
                                        tenant,
                                        agent,
                                        run.runId(),
                                        fence,
                                        scope,
                                        "outputs/late.txt",
                                        bytes("abc"),
                                        "text/plain",
                                        "test",
                                        Map.of()))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
        assertThat(objects.puts.get()).isEqualTo(1);
    }

    private String failedSqlPublication(boolean rawWrite) {
        FileVersionRepository failed = mock(FileVersionRepository.class, delegatesTo(versions));
        doThrow(
                        new org.springframework.dao.TransientDataAccessResourceException(
                                "fixture database unavailable"))
                .when(failed)
                .save(any());
        assertThatThrownBy(
                        () -> {
                            if (rawWrite)
                                catalog(failed)
                                        .recordWorkspaceFileWithWrite(
                                                tenant,
                                                agent,
                                                null,
                                                "outputs/recover.txt",
                                                bytes("abc"),
                                                "text/plain",
                                                "test",
                                                Map.of("label", "recovery"),
                                                () -> {
                                                    assertThat(
                                                                    TransactionSynchronizationManager
                                                                            .isActualTransactionActive())
                                                            .isFalse();
                                                });
                            else
                                catalog(failed)
                                        .recordWorkspaceFile(
                                                tenant,
                                                agent,
                                                null,
                                                "outputs/recover.txt",
                                                bytes("abc"),
                                                "text/plain",
                                                "test",
                                                Map.of("label", "recovery"));
                        })
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
        return objects.lastKey;
    }

    @Test
    void crashedPureProjectionIsRecoveredFromVerifiedStagedObject() throws Exception {
        String key = stageForRecovery(false);
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isEqualTo(1);
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
        assertThat(catalog.readCurrentFile(tenant, "outputs/stage.txt").orElseThrow().text())
                .isEqualTo("abc");
    }

    @Test
    void stagedUploadWithUnknownWorkspaceSideEffectIsNotReplayed() throws Exception {
        String key = stageForRecovery(true);
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isZero();
        assertThat(publication(key).status()).isEqualTo("STAGED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
    }

    @Test
    void cancelledAttemptCannotBeRecoveredEvenWithIntactBytes() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var run =
                orchestration.createDirectRun(
                        tenant, agent, session, null, "recovery", "recovery-" + UUID.randomUUID());
        MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class)
                .activateAttemptLease(
                        run.rootAttemptId(),
                        "recovery-owner",
                        OffsetDateTime.now().plusHours(1),
                        OffsetDateTime.now());
        var scope =
                new Execution(
                        run.rootTaskId(),
                        run.rootAgentRunId(),
                        run.rootAttemptId(),
                        "recovery-owner");
        var fence = runs.findCurrentSessionFence(run.runId(), org, user, agent).orElseThrow();
        FileVersionRepository failed = mock(FileVersionRepository.class, delegatesTo(versions));
        doThrow(
                        new org.springframework.dao.TransientDataAccessResourceException(
                                "fixture unavailable"))
                .when(failed)
                .save(any());
        assertThatThrownBy(
                        () ->
                                catalog(failed)
                                        .recordWorkspaceFileForAttempt(
                                                tenant,
                                                agent,
                                                run.runId(),
                                                fence,
                                                scope,
                                                "outputs/attempt.txt",
                                                bytes("abc"),
                                                "text/plain",
                                                "test",
                                                Map.of()))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
        String key = objects.lastKey;
        orchestration.cancel(tenant, agent, run.runId());
        due(key);
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isZero();
        assertThat(publication(key).status()).isEqualTo("ABORTED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
    }

    @Test
    void attemptFileCommitsVersionArtifactOutboxAndReceiptTogether() {
        var scope = activeScope();
        var file =
                taskWrite(
                        catalog,
                        scope,
                        "outputs/receipt.txt",
                        Map.of("private", "must-not-be-delivered"));
        assertThat(receipts(scope))
                .singleElement()
                .satisfies(
                        receipt -> {
                            assertThat(receipt.versionId()).isEqualTo(file.versionId());
                            assertThat(receipt.fileId()).isEqualTo(file.fileId());
                            assertThat(receipt.objectKey()).isEqualTo(file.objectKey());
                            assertThat(receipt.sha256()).isEqualTo(file.sha256());
                            assertThat(receipt.sizeBytes()).isEqualTo(3);
                            assertThat(receipt.artifactId())
                                    .isEqualTo(
                                            FileArtifactReceiptService.artifactId(
                                                    scope.execution().attemptId(),
                                                    file.versionId()));
                        });
        assertThat(db().countArtifactPublicationEvents(org)).isEqualTo(1);
        assertThat(db().countLinkedArtifactReceipts(org, scope.runId())).isEqualTo(1);
        assertThat(artifacts.findByRunId(scope.runId(), org))
                .singleElement()
                .satisfies(
                        a ->
                                assertThat(a.evidenceJson())
                                        .contains("\"producedInRun\":true")
                                        .doesNotContain("must-not-be-delivered"));
        assertThat(finishCheckpoint(scope, file).artifactCount()).isEqualTo(1);
    }

    @Test
    void userFileDoesNotCreateTaskArtifactOrPublicationEvent() {
        write("uploads/independent.txt", "abc");
        assertThat(db().countArtifactPublicationEvents(org)).isZero();
    }

    @Test
    void repeatedSameAttemptVersionReusesArtifactAndOutboxButKeepsBothReceipts() {
        var scope = activeScope();
        var first = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var repeated = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        assertThat(repeated.versionId()).isEqualTo(first.versionId());
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(artifacts.findByRunId(scope.runId(), org)).hasSize(1);
        assertThat(db().countArtifactPublicationEvents(org)).isEqualTo(1);
        assertThat(db().countLinkedArtifactReceipts(org, scope.runId())).isEqualTo(2);
        assertThat(receipts(scope)).hasSize(1);
        assertThat(finishCheckpoint(scope, repeated).artifactCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"artifact", "outbox", "link", "manifest"})
    void commitFaultRollsBackEveryReceiptAndRecoveryPublishesExactlyOnce(String stage) {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var artifactPort = artifacts;
        var eventPort = runs;
        var publicationPort = publications;
        var manifestPort = manifestRepository;
        if (stage.equals("artifact")) {
            artifactPort = mock(RunArtifactRepository.class, delegatesTo(artifacts));
            doAnswer(
                            invocation -> {
                                artifacts.insert(invocation.getArgument(0, NewRunArtifact.class));
                                throw new org.springframework.dao
                                        .TransientDataAccessResourceException(
                                        "fixture artifact commit lost");
                            })
                    .when(artifactPort)
                    .insert(any());
        } else if (stage.equals("outbox")) {
            eventPort = mock(RunOrchestrationRepository.class, delegatesTo(runs));
            doAnswer(
                            invocation -> {
                                var event = invocation.getArgument(0, NewOutboxMessage.class);
                                assertThat(event.payloadJson())
                                        .doesNotContain("objects/", "private", "secret");
                                runs.insertOutbox(event);
                                throw new org.springframework.dao
                                        .TransientDataAccessResourceException(
                                        "fixture event commit lost");
                            })
                    .when(eventPort)
                    .insertOutbox(any());
        } else if (stage.equals("link")) {
            publicationPort = mock(FilePublicationRepository.class, delegatesTo(publications));
            doAnswer(
                            invocation -> {
                                publications.linkArtifactReceipt(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2));
                                throw new org.springframework.dao
                                        .TransientDataAccessResourceException(
                                        "fixture receipt commit lost");
                            })
                    .when(publicationPort)
                    .linkArtifactReceipt(any(), any(), any());
        } else {
            manifestPort = mock(WorkspaceManifestRepository.class, delegatesTo(manifestRepository));
            doAnswer(
                            invocation -> {
                                manifestRepository.recordPublication(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2));
                                throw new org.springframework.dao
                                        .TransientDataAccessResourceException(
                                        "fixture manifest commit lost");
                            })
                    .when(manifestPort)
                    .recordPublication(any(), any(), any());
        }
        var faulty = catalog(versions, artifactPort, eventPort, publicationPort, manifestPort);
        assertThatThrownBy(
                        () ->
                                taskWrite(
                                        faulty,
                                        scope,
                                        "outputs/receipt.txt",
                                        Map.of("private", "secret")))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
        String key = objects.lastKey;
        assertThat(publication(key).status()).isEqualTo("STORED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(artifacts.findByRunId(scope.runId(), org)).isEmpty();
        assertThat(db().countArtifactPublicationEvents(org)).isZero();
        assertThat(db().countLinkedArtifactReceipts(org, scope.runId())).isZero();
        assertThat(manifestRepository.entries(org, scope.manifest().id()).get(0).publicationId())
                .isNull();
        due(key);
        var recovery =
                new FilePublicationRecoveryJob(
                        publications, catalog(versions), properties, manager);
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        assertThat(recovery.recoverOnce()).isZero();
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
        assertThat(artifacts.findByRunId(scope.runId(), org)).hasSize(1);
        assertThat(db().countArtifactPublicationEvents(org)).isEqualTo(1);
        assertThat(db().countLinkedArtifactReceipts(org, scope.runId())).isEqualTo(1);
        var file =
                files.findByOrgIdAndUserIdAndLogicalPath(org, user, "outputs/receipt.txt")
                        .orElseThrow();
        var version = catalog.listVersions(tenant, "outputs/receipt.txt").get(0);
        finishCheckpoint(
                scope,
                new FileCatalogService.FileRecord(
                        file.getId(),
                        version.id(),
                        "outputs/receipt.txt",
                        version.versionNo(),
                        key,
                        "minio",
                        3,
                        publication(key).sha256()));
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id())).isEqualTo("READY");
    }

    @Test
    void missingOutboxRejectsCheckpointAndCannotBeHiddenBySameVersionRepublish() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        db().deleteArtifactReceiptEvent(org, receipts(scope).get(0).eventId());
        assertThatThrownBy(() -> finishCheckpoint(scope, file))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
        assertThatThrownBy(() -> taskWrite(catalog, scope, "outputs/receipt.txt", Map.of()))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISMATCH");
        assertThat(catalog.listVersions(tenant, "outputs/receipt.txt")).hasSize(1);
        assertThat(objects.puts.get()).isEqualTo(1);
    }

    @Test
    void mismatchedPersistedVersionDigestRejectsCheckpoint() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        db().corruptArtifactVersionDigest(org, file.versionId(), "wrong-digest");
        assertThatThrownBy(() -> finishCheckpoint(scope, file))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
    }

    @Test
    void anotherRunTaskAttemptOrOrganizationCannotUseExistingReceipt() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        assertThat(
                        publications.artifactReceipts(
                                UUID.randomUUID(),
                                scope.runId(),
                                scope.execution().taskId(),
                                scope.execution().attemptId()))
                .isEmpty();
        assertThat(
                        publications.artifactReceipts(
                                org,
                                scope.runId(),
                                UUID.randomUUID(),
                                scope.execution().attemptId()))
                .isEmpty();
        assertThat(
                        publications.artifactReceipts(
                                org, scope.runId(), scope.execution().taskId(), UUID.randomUUID()))
                .isEmpty();
        var other = activeScope();
        assertThatThrownBy(() -> finishCheckpoint(other, file))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
        assertThat(artifacts.findByRunId(other.runId(), org)).isEmpty();
    }

    @Test
    void cancellationAfterFileCommitRejectsWorkspaceSuccess() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        orchestration.cancel(tenant, agent, scope.runId());
        assertThatThrownBy(() -> finishCheckpoint(scope, file))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
    }

    @Test
    void expiredAttemptCannotFinishEvenWithAnIntactPublicationReceipt() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        db().activateAttemptLease(
                        scope.execution().attemptId(),
                        scope.execution().leaseOwner(),
                        OffsetDateTime.now().minusMinutes(1),
                        OffsetDateTime.now());
        assertThatThrownBy(() -> finishCheckpoint(scope, file))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
    }

    @Test
    void cancelDuringObjectPutCannotPublishVersionArtifactOrEvent() throws Exception {
        var scope = activeScope();
        objects.blockFirst = true;
        Future<?> slow = async(() -> taskWrite(catalog, scope, "outputs/receipt.txt", Map.of()));
        assertThat(objects.entered.await(3, TimeUnit.SECONDS)).isTrue();
        orchestration.cancel(tenant, agent, scope.runId());
        objects.release.countDown();
        assertThatThrownBy(() -> slow.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("SESSION_EXECUTION_REVOKED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(artifacts.findByRunId(scope.runId(), org)).isEmpty();
        assertThat(db().countArtifactPublicationEvents(org)).isZero();
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
    }

    @Test
    void immutableEvidenceDistinguishesOldFilesFromFilesProducedBeforeProjection() {
        var prior = write("outputs/prior.txt", "abc");
        var baseline = new WorkspaceCheckpointContext(true, catalog.activeFileVersions(tenant));
        var scope = activeScope(java.util.List.of("outputs/prior.txt", "outputs/during.txt"));
        write("outputs/during.txt", "abc");
        taskWrite(
                catalog,
                scope,
                "outputs/prior.txt",
                baseline.publicationMetadata("/outputs/prior.txt"));
        taskWrite(
                catalog,
                scope,
                "outputs/during.txt",
                baseline.publicationMetadata("/outputs/during.txt"));
        assertThat(artifacts.findByRunId(scope.runId(), org))
                .filteredOn(a -> a.fileVersionId().equals(prior.versionId()))
                .singleElement()
                .satisfies(a -> assertThat(a.evidenceJson()).contains("\"producedInRun\":false"));
        assertThat(artifacts.findByRunId(scope.runId(), org))
                .filteredOn(a -> a.logicalPath().equals("outputs/during.txt"))
                .singleElement()
                .satisfies(a -> assertThat(a.evidenceJson()).contains("\"producedInRun\":true"));
        assertThat(objects.puts.get()).isEqualTo(2);
    }

    @Test
    void missingExpectedFileRejectsEvenWhenTheInMemoryCountLooksComplete() {
        var scope = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/missing.txt"));
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(2, 2, 0, 6, true, java.util.List.of()));
        var checkpoint = boundCheckpoint(scope, file);
        assertThatThrownBy(() -> publishCheckpoint(scope, checkpoint))
                .hasMessageContaining("WORKSPACE_MANIFEST_INVENTORY_MISMATCH");
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id())).isEqualTo("SCANNED");
        assertThat(manifestRepository.entries(org, scope.manifest().id()))
                .filteredOn(e -> e.publicationId() == null)
                .hasSize(1);
    }

    @Test
    void uncompletedTransferScanCannotBecomeReady() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        assertThatThrownBy(() -> publishCheckpoint(scope, boundCheckpoint(scope, file)))
                .hasMessageContaining("WORKSPACE_MANIFEST_SCAN_INCOMPLETE");
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id()))
                .isEqualTo("DISCOVERED");
    }

    @Test
    void unexpectedPathRollsBackCatalogArtifactAndManifestLink() {
        var scope = activeScope();
        assertThatThrownBy(() -> taskWrite(catalog, scope, "outputs/unexpected.txt", Map.of()))
                .hasMessageContaining("WORKSPACE_MANIFEST_PUBLICATION_MISMATCH");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(artifacts.findByRunId(scope.runId(), org)).isEmpty();
        assertThat(manifestRepository.entries(org, scope.manifest().id()).get(0).publicationId())
                .isNull();
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isZero();
    }

    @Test
    void unexpectedBytesCannotBePublishedAgainstFrozenInventory() {
        var scope = activeScope();
        assertThatThrownBy(
                        () ->
                                catalog.recordWorkspaceFileForAttempt(
                                        tenant,
                                        agent,
                                        scope.runId(),
                                        scope.fence(),
                                        scope.execution(),
                                        "outputs/receipt.txt",
                                        bytes("xyz"),
                                        "text/plain",
                                        "test",
                                        Map.of(
                                                "workspaceManifestId",
                                                scope.manifest().id().toString())))
                .hasMessageContaining("WORKSPACE_MANIFEST_PUBLICATION_MISMATCH");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(artifacts.findByRunId(scope.runId(), org)).isEmpty();
        assertThat(db().countArtifactPublicationEvents(org)).isZero();
    }

    @Test
    void persistedInventoryCorruptionIsDetectedByItsFingerprint() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        db().corruptWorkspaceManifestEntry(
                        org, scope.manifest().id(), "outputs/receipt.txt", digest("xyz"));
        assertThatThrownBy(() -> publishCheckpoint(scope, boundCheckpoint(scope, file)))
                .hasMessageContaining("WORKSPACE_MANIFEST_INVENTORY_MISMATCH");
    }

    @Test
    void newerFrozenInventoryRevokesLateWritersAndCompletionOfThePreviousScan() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        var next =
                manifestService.freeze(
                        scope.manifest().scope(),
                        new WorkspaceProjectionPlan(
                                java.util.List.of(
                                        new WorkspaceProjectionPlan.Entry(
                                                "outputs/next.txt",
                                                "outputs/next.txt",
                                                3,
                                                digest("abc"),
                                                false)),
                                1,
                                3));
        assertThat(next.sequence()).isEqualTo(scope.manifest().sequence() + 1);
        assertThatThrownBy(() -> taskWrite(catalog, scope, "outputs/receipt.txt", Map.of()))
                .hasMessageContaining("WORKSPACE_MANIFEST_PUBLICATION_MISMATCH");
        assertThatThrownBy(() -> publishCheckpoint(scope, boundCheckpoint(scope, file)))
                .hasMessageContaining("WORKSPACE_MANIFEST_SUPERSEDED");
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(db().workspaceManifestStatus(org, next.id())).isEqualTo("DISCOVERED");
    }

    @Test
    void changedLeaseOwnerCannotUseAnOlderManifestEvenWithALiveAttemptAndValidFiles() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        db().activateAttemptLease(
                        scope.execution().attemptId(),
                        "new-owner",
                        OffsetDateTime.now().plusHours(1),
                        OffsetDateTime.now());
        assertThatThrownBy(() -> publishCheckpoint(scope, boundCheckpoint(scope, file)))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
    }

    @Test
    void emptyCompleteInventoryCanBeReadyAndVerifiedIdempotently() {
        var scope = activeScope(java.util.List.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(0, 0, 0, 0, true, java.util.List.of()));
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        checkpoint.projectionSucceeded(0);
        assertThat(publishCheckpoint(scope, checkpoint).artifactCount()).isZero();
        assertThat(publishCheckpoint(scope, checkpoint).artifactCount()).isZero();
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id())).isEqualTo("READY");
    }

    @Test
    void forgedManifestEmployeeCannotFreezeAnotherEmployeesAttempt() {
        var scope = activeScope();
        var s = scope.manifest().scope();
        assertThatThrownBy(
                        () ->
                                manifestService.freeze(
                                        new WorkspaceManifestRepository.Scope(
                                                s.orgId(),
                                                UUID.randomUUID(),
                                                s.agentId(),
                                                s.sessionId(),
                                                s.sessionGeneration(),
                                                s.runId(),
                                                s.execution()),
                                        new WorkspaceProjectionPlan(java.util.List.of(), 0, 0)))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
    }

    @Test
    void unchangedObjectStillRequiresAnActualIntegrityReadBeforeReceiptReuse() {
        var scope = activeScope();
        taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        objects.corrupt = true;
        assertThatThrownBy(() -> taskWrite(catalog, scope, "outputs/receipt.txt", Map.of()))
                .hasMessageContaining("FILE_PUBLICATION_INTEGRITY_FAILED");
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(db().countLinkedArtifactReceipts(org, scope.runId())).isEqualTo(1);
    }

    @Test
    void repeatedProjectionInTheSameRuntimeContextUsesOnlyTheNewFrozenScan() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var checkpoint = boundCheckpoint(scope, file);
        checkpoint.failed(
                "workspace_projection",
                new IllegalStateException("previous transient projection failure"));
        var next =
                manifestService.freeze(
                        scope.manifest().scope(),
                        new WorkspaceProjectionPlan(
                                java.util.List.of(
                                        new WorkspaceProjectionPlan.Entry(
                                                "outputs/receipt.txt",
                                                "outputs/receipt.txt",
                                                3,
                                                digest("abc"),
                                                false)),
                                1,
                                3));
        checkpoint.planned(next);
        assertThat(checkpoint.files()).isEmpty();
        var nextScope = new Scope(scope.runId(), scope.execution(), scope.fence(), next);
        var repeated = taskWrite(catalog, nextScope, "outputs/receipt.txt", Map.of());
        checkpoint.recordFile(repeated);
        checkpoint.projectionSucceeded(1);
        manifestService.scanCompleted(
                next, new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        assertThat(publishCheckpoint(nextScope, checkpoint).artifactCount()).isEqualTo(1);
        assertThat(objects.puts.get()).isEqualTo(1);
        assertThat(db().workspaceManifestStatus(org, next.id())).isEqualTo("READY");
    }

    @Test
    void reusedRuntimeCheckpointCannotSwitchToAnotherBusinessTask() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var checkpoint = boundCheckpoint(scope, file);
        var other = activeScope();
        assertThatThrownBy(() -> checkpoint.planned(other.manifest()))
                .hasMessageContaining("WORKSPACE_MANIFEST_SCOPE_CHANGED");
        assertThat(checkpoint.manifest().id()).isEqualTo(scope.manifest().id());
        assertThat(checkpoint.files()).containsExactly(file);
    }

    @Test
    void restoreSelectsChangedVersionAndExcludesHistoricalPathsFromTheSameAttempt() {
        properties.getFileStore().setMaxUserBytes(100);
        var first = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/removed.txt"));
        taskWrite(catalog, first, "outputs/receipt.txt", Map.of());
        taskWrite(catalog, first, "outputs/removed.txt", Map.of());
        var next = restoreRescan(first, java.util.List.of("outputs/receipt.txt"), "xyz");
        var file =
                catalog.recordWorkspaceFileForAttempt(
                                tenant,
                                agent,
                                next.runId(),
                                next.fence(),
                                next.execution(),
                                "outputs/receipt.txt",
                                bytes("xyz"),
                                "text/plain",
                                "test",
                                Map.of("workspaceManifestId", next.manifest().id().toString()))
                        .orElseThrow();
        var checkpoint = finishCheckpoint(next, file);
        storeRestoreCheckpoint(next, checkpoint);
        UUID retry = restoreRetry(first);
        var restored =
                restoreService()
                        .prepare(
                                tenant,
                                org,
                                retry,
                                "restore-owner",
                                WorkspaceIsolationMode.ATTEMPT_ISOLATED)
                        .orElseThrow();
        assertThat(artifacts.findByAttemptId(first.execution().attemptId(), org)).hasSize(3);
        assertThat(restored.files())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.path()).isEqualTo("outputs/receipt.txt");
                            assertThat(f.content()).isEqualTo(bytes("xyz"));
                        });
        assertThat(restored.checkpointUri()).contains(next.manifest().id().toString());
    }

    @Test
    void normalPublicationPinsThePersistentLeaseWithoutTheOuterRuntimeContextBinding() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        UUID lease = UUID.randomUUID();
        sandboxLeases.insert(
                new io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.NewSandboxLease(
                        lease,
                        org,
                        user,
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().attemptId(),
                        "opensandbox",
                        "test",
                        "[]",
                        "resource-owner",
                        OffsetDateTime.now().plusMinutes(1),
                        OffsetDateTime.now()));
        sandboxLeases.release(lease, org, OffsetDateTime.now());
        var published = finishCheckpoint(scope, file);
        var stored = sandboxLeases.findById(lease, org).orElseThrow();
        assertThat(stored.status()).isEqualTo("RELEASED");
        assertThat(stored.workspaceSnapshotUri()).isEqualTo(published.uri());
        assertThat(stored.workspaceVersion()).isEqualTo(published.version());
        assertThat(stored.workspaceSnapshotUri()).contains(scope.manifest().id().toString());
    }

    @Test
    void pinnedReadyCheckpointRemainsRestorableAfterANewerIncompleteScan() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var checkpoint = finishCheckpoint(scope, file);
        storeRestoreCheckpoint(scope, checkpoint);
        var incomplete = restoreRescan(scope, java.util.List.of("outputs/later.txt"), "xyz");
        UUID retry = restoreRetry(scope);
        var restored =
                restoreService()
                        .prepare(
                                tenant,
                                org,
                                retry,
                                "restore-owner",
                                WorkspaceIsolationMode.ATTEMPT_ISOLATED)
                        .orElseThrow();
        assertThat(db().workspaceManifestStatus(org, incomplete.manifest().id()))
                .isEqualTo("DISCOVERED");
        assertThat(restored.files())
                .singleElement()
                .satisfies(f -> assertThat(f.content()).isEqualTo(bytes("abc")));
        assertThat(restored.checkpointUri()).isEqualTo(checkpoint.uri());
    }

    @Test
    void catalogRenameAfterSnapshotDoesNotChangeRestoredPathOrVersion() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        storeRestoreCheckpoint(scope, finishCheckpoint(scope, file));
        catalog.moveFile(tenant, agent, session, "outputs/receipt.txt", "outputs/renamed.txt");
        UUID retry = restoreRetry(scope);
        var restored =
                restoreService()
                        .prepare(
                                tenant,
                                org,
                                retry,
                                "restore-owner",
                                WorkspaceIsolationMode.DEDICATED_SANDBOX)
                        .orElseThrow();
        assertThat(restored.files())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.path()).isEqualTo("outputs/receipt.txt");
                            assertThat(f.content()).isEqualTo(bytes("abc"));
                        });
    }

    @Test
    void explicitlyEmptyReadyManifestDoesNotResurrectOlderArtifacts() {
        var scope = activeScope();
        taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var empty = restoreRescan(scope, java.util.List.of(), "abc");
        manifestService.scanCompleted(
                empty.manifest(),
                new WorkspaceProjectionReport(0, 0, 0, 0, true, java.util.List.of()));
        var context = new WorkspaceCheckpointContext(true);
        context.planned(empty.manifest());
        context.projectionSucceeded(0);
        storeRestoreCheckpoint(empty, publishCheckpoint(empty, context));
        UUID retry = restoreRetry(scope);
        assertThat(
                        restoreService()
                                .prepare(
                                        tenant,
                                        org,
                                        retry,
                                        "restore-owner",
                                        WorkspaceIsolationMode.RUN_ISOLATED)
                                .orElseThrow()
                                .files())
                .isEmpty();
        assertThat(artifacts.findByAttemptId(scope.execution().attemptId(), org)).hasSize(1);
    }

    @Test
    void sessionResetDuringActualObjectReadRevokesRestoreWithoutHoldingSqlLocks() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        storeRestoreCheckpoint(scope, finishCheckpoint(scope, file));
        UUID retry = restoreRetry(scope);
        objects.afterGet = () -> db().advanceSessionGeneration(session);
        assertThatThrownBy(
                        () ->
                                restoreService()
                                        .prepare(
                                                tenant,
                                                org,
                                                retry,
                                                "restore-owner",
                                                WorkspaceIsolationMode.ATTEMPT_ISOLATED))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(2);
    }

    @Test
    void replacedObjectBytesCannotBeRestoredFromAValidManifestReceipt() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        storeRestoreCheckpoint(scope, finishCheckpoint(scope, file));
        UUID retry = restoreRetry(scope);
        objects.corrupt = true;
        assertThatThrownBy(
                        () ->
                                restoreService()
                                        .prepare(
                                                tenant,
                                                org,
                                                retry,
                                                "restore-owner",
                                                WorkspaceIsolationMode.ATTEMPT_ISOLATED))
                .hasMessageContaining("content digest mismatch");
    }

    @Test
    void anotherEmployeeCannotUseACheckpointEvenWithinTheSameOrganization() {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        storeRestoreCheckpoint(scope, finishCheckpoint(scope, file));
        UUID retry = restoreRetry(scope);
        var other =
                new TenantContext(
                        org.toString(), UUID.randomUUID().toString(), "member", "standard", 2, 0);
        assertThatThrownBy(
                        () ->
                                restoreService()
                                        .prepare(
                                                other,
                                                org,
                                                retry,
                                                "restore-owner",
                                                WorkspaceIsolationMode.ATTEMPT_ISOLATED))
                .hasMessageContaining("SESSION_EXECUTION_REVOKED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfEnvironmentVariable(named = "SAAS_CHECKPOINT_OPENSANDBOX_TEST", matches = "true")
    void verifiedCatalogPlanRestoresIntoAFreshRealOpenSandboxWithoutMutableHistoricalFiles(
            boolean empty) throws Exception {
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var publication = finishCheckpoint(scope, file);
        if (empty) {
            scope = restoreRescan(scope, java.util.List.of(), "abc");
            manifestService.scanCompleted(
                    scope.manifest(),
                    new WorkspaceProjectionReport(0, 0, 0, 0, true, java.util.List.of()));
            var context = new WorkspaceCheckpointContext(true);
            context.planned(scope.manifest());
            context.projectionSucceeded(0);
            publication = publishCheckpoint(scope, context);
        }
        storeRestoreCheckpoint(scope, publication);
        UUID retry = restoreRetry(scope);
        var plan =
                restoreService()
                        .prepare(
                                tenant,
                                org,
                                retry,
                                "restore-owner",
                                WorkspaceIsolationMode.ATTEMPT_ISOLATED)
                        .orElseThrow();
        var options = new io.agentscope.extensions.sandbox.opensandbox.OpenSandboxClientOptions();
        options.setApiBaseUrl(
                System.getenv()
                        .getOrDefault("OPENSANDBOX_API_BASE_URL", "http://127.0.0.1:18081/v1"));
        options.setImage(
                System.getenv().getOrDefault("SAAS_SANDBOX_OPENSANDBOX_IMAGE", "ubuntu:latest"));
        options.setWorkspaceRoot("/workspace");
        options.setWaitTimeoutSeconds(180);
        var spec = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        spec.setRoot("/workspace");
        var client = new io.agentscope.extensions.sandbox.opensandbox.OpenSandboxClient();
        var sandbox = client.create(spec, null, options);
        var stateStore = mock(io.agentscope.harness.agent.sandbox.SessionSandboxStateStore.class);
        var lifecycleManager =
                new io.agentscope.harness.agent.sandbox.SandboxManager(
                        client, stateStore, "restore-probe");
        var remote =
                new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
                        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore(),
                        c -> java.util.List.of("restore", "probe"));
        var context = io.agentscope.core.agent.RuntimeContext.empty();
        remote.write(context, "/outputs/historical.txt", "must not restore");
        remote.write(context, "/outputs/receipt.txt", "wrong current version");
        var filesystem =
                new io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem();
        filesystem.configureRemoteFallback(remote);
        var middleware =
                new io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware(
                        lifecycleManager, filesystem);
        context.put(
                io.agentscope.harness.agent.sandbox.SandboxContext.class,
                io.agentscope.harness.agent.sandbox.SandboxContext.builder()
                        .externalSandbox(sandbox)
                        .build());
        context.put(io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.class, plan);
        try {
            middleware.acquireForCall(context);
            String command =
                    "test ! -e /workspace/outputs/historical.txt && "
                            + (empty
                                    ? "test ! -e /workspace/outputs/receipt.txt && printf"
                                            + " 'empty-checkpoint-ok'"
                                    : "cat /workspace/outputs/receipt.txt");
            var result = sandbox.exec(context, command, 30);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo(empty ? "empty-checkpoint-ok" : "abc");
        } finally {
            try {
                middleware.releaseForCall(context);
            } finally {
                sandbox.shutdown();
            }
        }
    }

    private Scope restoreRescan(Scope scope, java.util.List<String> paths, String content) {
        var plan =
                new WorkspaceProjectionPlan(
                        paths.stream()
                                .map(
                                        p ->
                                                new WorkspaceProjectionPlan.Entry(
                                                        p,
                                                        p,
                                                        bytes(content).length,
                                                        digest(content),
                                                        false))
                                .toList(),
                        paths.size(),
                        (long) bytes(content).length * paths.size());
        var m = manifestService.freeze(scope.manifest().scope(), plan);
        return new Scope(scope.runId(), scope.execution(), scope.fence(), m);
    }

    private void storeRestoreCheckpoint(
            Scope scope, WorkspaceArtifactService.Publication checkpoint) {
        UUID lease = UUID.randomUUID();
        sandboxLeases.insert(
                new io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.NewSandboxLease(
                        lease,
                        org,
                        user,
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().attemptId(),
                        "opensandbox",
                        "test",
                        "[]",
                        "independent-resource-owner",
                        OffsetDateTime.now().plusMinutes(1),
                        OffsetDateTime.now()));
        assertThat(sandboxLeases.checkpoint(lease, org, checkpoint.uri(), checkpoint.version()))
                .isEqualTo(1);
        sandboxLeases.release(lease, org, OffsetDateTime.now());
    }

    private UUID restoreRetry(Scope scope) {
        UUID attempt = UUID.randomUUID();
        db().insertRunningAttempt(
                        attempt,
                        org,
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().agentRunId(),
                        2,
                        "restore-owner",
                        OffsetDateTime.now().plusHours(1),
                        "restore-" + attempt,
                        OffsetDateTime.now());
        return attempt;
    }

    private WorkspaceCheckpointRestoreService restoreService() {
        return new WorkspaceCheckpointRestoreService(
                sandboxLeases, artifacts, catalog, mapper, manifestRepository, properties, manager);
    }

    private WorkspaceCheckpointContext boundCheckpoint(
            Scope scope, FileCatalogService.FileRecord file) {
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        checkpoint.recordFile(file);
        checkpoint.projectionSucceeded(1);
        return checkpoint;
    }

    private WorkspaceArtifactService.Publication publishCheckpoint(
            Scope scope, WorkspaceCheckpointContext checkpoint) {
        return workspaceArtifacts.publish(
                org,
                scope.runId(),
                scope.execution().taskId(),
                scope.execution().attemptId(),
                null,
                checkpoint);
    }

    @Test
    void archiveLeaseCompletesStoredOutputWithoutRenewingOrReplayingBusinessAttempt() {
        var scope = pendingArchiveScope();
        String key = objects.lastKey;
        var service = archiveService();
        UUID id =
                service.defer(
                        scannedCheckpoint(scope),
                        "{\"summary\":\"done\"}",
                        java.util.List.of(TextBlock.builder().text("archived answer").build()),
                        true);
        var job = archive(id);
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("ARCHIVING");
        assertThat(db().taskState(scope.execution().taskId()).status()).isEqualTo("ARCHIVING");
        assertThatThrownBy(() -> orchestration.markSucceeded(tenant, agent, scope.runId()))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(
                        durableLeases.heartbeat(
                                scope.execution().attemptId(), scope.execution().leaseOwner()))
                .isFalse();
        assertThat(
                        durableLeases.succeed(
                                scope.execution().attemptId(), scope.execution().leaseOwner()))
                .isFalse();
        assertThat(
                        new FilePublicationRecoveryJob(publications, catalog, properties, manager)
                                .recoverOnce())
                .isZero();
        assertThat(runtimeBodies.references(job.bodyId())).isEqualTo(1);
        assertThat(service.recover(job)).isTrue();
        assertThat(archive(id).status()).isEqualTo("COMPLETED");
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("SUCCEEDED");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().taskState(scope.execution().taskId()).outputJson())
                .contains("workspace-catalog://", "done");
        assertThat(db().runState(scope.runId()).status()).isEqualTo("SUCCEEDED");
        assertThat(db().countSessionMessages(session)).isEqualTo(1);
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
        assertThat(receipts(scope)).hasSize(1);
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id())).isEqualTo("READY");
        assertThat(service.recover(job)).isFalse();
        assertThat(db().countSessionMessages(session)).isEqualTo(1);
        assertThat(objects.puts.get())
                .isEqualTo(2); // File plus pending result; no provider reread/PUT replay.
        assertThat(runtimeBodies.references(job.bodyId())).isZero();
    }

    @Test
    void archiveRequiresCompleteScanAndEveryMaterializedSource() {
        var scope = activeScope();
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        assertThatThrownBy(
                        () -> archiveService().defer(checkpoint, "{}", java.util.List.of(), false))
                .hasMessageContaining("SCAN_INCOMPLETE");
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        assertThatThrownBy(
                        () -> archiveService().defer(checkpoint, "{}", java.util.List.of(), false))
                .hasMessageContaining("BYTES_MISSING");
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("RUNNING");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void archiveCompletesMixedPublishedAndStoredInventory() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/second.txt"));
        taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        failArchiveWrite(scope, "outputs/second.txt");
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(receipts(scope)).hasSize(2);
        assertThat(db().countArtifactPublicationEvents(org)).isEqualTo(2);
        var output = mapper.readTree(db().taskState(scope.execution().taskId()).outputJson());
        if (output.isTextual()) output = mapper.readTree(output.asText());
        assertThat(output.path("evidence").get(0).path("artifactCount").asInt()).isEqualTo(2);
    }

    @Test
    void staleArchiveTokenCannotPublishAfterAnotherWorkerTakesOver() {
        var scope = pendingArchiveScope();
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var job = archive(id);
        UUID stale = UUID.randomUUID();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            assertThat(archives.lockScope(job)).isTrue();
                            assertThat(
                                            archives.claim(
                                                    job,
                                                    stale,
                                                    OffsetDateTime.now(),
                                                    OffsetDateTime.now().plusSeconds(60),
                                                    8))
                                    .isEqualTo(1);
                        });
        db().expireArchiveLease(id, OffsetDateTime.now().minusSeconds(1));
        var p = archives.publications(job).get(0);
        assertThatThrownBy(
                        () ->
                                catalog.recoverPublication(
                                        p,
                                        stale,
                                        new WorkspaceArchiveRepository.Authority(id, stale),
                                        OffsetDateTime.now().plusSeconds(60)))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(durableLeases.finishArchive(job, stale, "{}", null, archives)).isFalse();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().countArtifactPublicationEvents(org)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reset", "cancel"})
    void resetOrCancelRevokesArchiveAndNeverResurrectsOutput(String action) {
        var scope = pendingArchiveScope();
        var service = archiveService();
        UUID id =
                service.defer(
                        scannedCheckpoint(scope),
                        "{}",
                        java.util.List.of(TextBlock.builder().text("late answer").build()),
                        true);
        var job = archive(id);
        if (action.equals("reset")) db().advanceSessionGeneration(session);
        else {
            orchestration.cancel(tenant, agent, scope.runId());
            assertThat(db().agentRunState(scope.execution().agentRunId()).status())
                    .isEqualTo("CANCELLED");
        }
        assertThat(service.recover(job)).isFalse();
        assertThat(archive(id).status()).isEqualTo("CANCELLED");
        assertThat(catalog.listActiveFiles(tenant)).isEmpty();
        assertThat(db().countSessionMessages(session)).isZero();
        assertThat(receipts(scope)).isEmpty();
        assertThat(runtimeBodies.references(job.bodyId())).isZero();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void archiveDeadlineFailsTerminalWithoutBusinessRetry() {
        var scope = pendingArchiveScope();
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        db().expireArchiveDeadline(id, OffsetDateTime.now().minusSeconds(1));
        db().archiveDue(id, OffsetDateTime.now().plusDays(1));
        assertThat(archives.candidates(OffsetDateTime.now(), 1000))
                .anyMatch(j -> j.id().equals(id));
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(archive(id).status()).isEqualTo("FAILED");
        assertThat(db().taskState(scope.execution().taskId()).status()).isEqualTo("FAILED");
        assertThat(db().runState(scope.runId()).status()).isEqualTo("FAILED");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void retainedArchiveBlocksQuotaAndPathMutationEvenWhenFileLeaseExpires() {
        var scope = pendingArchiveScope();
        properties.getRuntimeArchive().setBodyGraceSeconds(60);
        String key = objects.lastKey;
        UUID id =
                archiveService().defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        due(key);
        assertThat(publications.reserved(org, user, null, OffsetDateTime.now())).isEqualTo(3);
        assertThat(publications.pathBusy(org, user, "outputs/receipt.txt", OffsetDateTime.now()))
                .isTrue();
        assertThatThrownBy(() -> write("outputs/receipt.txt", "new")).hasMessageContaining("409");
        properties.getFileStore().setMaxUserBytes(3);
        assertThatThrownBy(() -> write("outputs/other.txt", "x")).hasMessageContaining("507");
        assertThat(publications.candidates(OffsetDateTime.now(), 10, 100))
                .noneMatch(p -> p.id().equals(publication(key).id()));
        assertThat(runtimeBodies.candidates(OffsetDateTime.now().plusSeconds(600), 10, 100))
                .noneMatch(body -> body.id().equals(archive(id).bodyId()));
        assertThat(
                        runtimeBodies.claim(
                                archive(id).bodyId(),
                                UUID.randomUUID(),
                                OffsetDateTime.now().plusSeconds(120),
                                OffsetDateTime.now().plusSeconds(240),
                                10))
                .isZero();
    }

    @Test
    void archiveIntegrityFailureRetainsPendingResultAndDoesNotExecuteTaskAgain() {
        var scope = pendingArchiveScope();
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var job = archive(id);
        var bodyScope = new RuntimeMessageRepository.Scope(org, user, agent, session, "", "");
        var body = runtimeBodies.findOwned(bodyScope, job.bodyId()).orElseThrow();
        byte[] original = objects.data.put(body.objectKey(), bytes("corrupt"));
        assertThatThrownBy(() -> service.recover(job)).hasMessageContaining("RECOVERY_DEFERRED");
        assertThat(archive(id).status()).isEqualTo("PENDING");
        assertThat(db().taskState(scope.execution().taskId()).status()).isEqualTo("ARCHIVING");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        objects.data.put(body.objectKey(), original);
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recover(archive(id))).isTrue();
    }

    @Test
    void archiveCatalogRetryRelinquishesOnlyItsOwnFileLease() {
        var scope = pendingArchiveScope();
        String key = objects.lastKey;
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var faulty = mock(FileVersionRepository.class, delegatesTo(versions));
        doThrow(
                        new org.springframework.dao.TransientDataAccessResourceException(
                                "fixture recovery commit lost"))
                .when(faulty)
                .save(any());
        var retrying =
                new WorkspaceArchiveService(
                        archives,
                        manifestRepository,
                        manifestService,
                        publications,
                        catalog(faulty),
                        new RuntimeBodyService(
                                runtimeBodies, runtimeMessages, stores, properties, manager),
                        chats,
                        durableLeases,
                        sandboxLeases,
                        runs,
                        mapper,
                        properties,
                        manager);
        assertThatThrownBy(() -> retrying.recover(archive(id)))
                .hasMessageContaining("RECOVERY_DEFERRED");
        assertThat(archive(id).status()).isEqualTo("PENDING");
        assertThat(
                        publications
                                .recovery(org, user, publication(key).id())
                                .orElseThrow()
                                .recoveryToken())
                .isNull();
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void archiveCompletionStillHonorsTaskContractAndDoesNotDeliverFailedAnswer() {
        var scope = pendingArchiveScope();
        db().updateTaskCompletionContract(scope.execution().taskId(), "[\"report file\"]", "[]");
        var service = archiveService();
        UUID id =
                service.defer(
                        scannedCheckpoint(scope),
                        "{}",
                        java.util.List.of(TextBlock.builder().text("not verified").build()),
                        true);
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(archive(id).status()).isEqualTo("FAILED");
        assertThat(db().runState(scope.runId()).failureCode()).isEqualTo("VERIFICATION_FAILED");
        assertThat(db().countSessionMessages(session)).isZero();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void archivedReceiptUpdatesOnlySnapshotMetadataOnAnAlreadyReleasedSandboxLease() {
        var scope = pendingArchiveScope();
        UUID lease = UUID.randomUUID();
        sandboxLeases.insert(
                new io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.NewSandboxLease(
                        lease,
                        org,
                        user,
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().attemptId(),
                        "opensandbox",
                        "test",
                        "[]",
                        "sandbox-owner",
                        OffsetDateTime.now().plusMinutes(1),
                        OffsetDateTime.now()));
        sandboxLeases.release(lease, org, OffsetDateTime.now());
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        assertThat(service.recover(archive(id))).isTrue();
        var released = sandboxLeases.findById(lease, org).orElseThrow();
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.workspaceSnapshotUri())
                .contains(scope.execution().attemptId().toString());
        assertThat(released.workspaceVersion()).hasSize(64);
    }

    @Test
    void forgedEmployeeCannotRevokeOrConsumeAnExistingArchiveJob() {
        var scope = pendingArchiveScope();
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var job = archive(id);
        var forged =
                new WorkspaceArchiveRepository.Job(
                        job.id(),
                        job.orgId(),
                        UUID.randomUUID(),
                        job.agentId(),
                        job.sessionId(),
                        job.sessionGeneration(),
                        job.runId(),
                        job.taskId(),
                        job.agentRunId(),
                        job.attemptId(),
                        job.originalOwner(),
                        job.manifestId(),
                        job.bodyId(),
                        job.bodyBytes(),
                        job.status(),
                        job.token(),
                        job.leaseUntil(),
                        job.deadlineAt(),
                        job.nextAttemptAt(),
                        job.attempts(),
                        job.createdAt());
        assertThat(service.recover(forged)).isFalse();
        assertThat(archive(id).status()).isEqualTo("PENDING");
        assertThat(service.recover(job)).isTrue();
    }

    @Test
    void archiveSettlementRechecksAuthorityAfterObtainingLocks() {
        var scope = pendingArchiveScope();
        UUID id =
                archiveService().defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var job = archive(id);
        UUID token = UUID.randomUUID();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            assertThat(archives.lockScope(job)).isTrue();
                            assertThat(
                                            archives.claim(
                                                    job,
                                                    token,
                                                    OffsetDateTime.now(),
                                                    OffsetDateTime.now().plusSeconds(60),
                                                    8))
                                    .isEqualTo(1);
                        });
        var boundary = mock(WorkspaceArchiveRepository.class, delegatesTo(archives));
        var checks = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (checks.incrementAndGet() != 1) return false;
                            return archives.lockCompletion(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    invocation.getArgument(3));
                        })
                .when(boundary)
                .lockCompletion(any(), any(), any(), anyInt());
        assertThat(durableLeases.finishArchive(archive(id), token, "{}", null, boundary)).isFalse();
        assertThat(checks.get()).isEqualTo(2);
        assertThat(archive(id).status()).isEqualTo("RUNNING");
        assertThat(db().taskState(scope.execution().taskId()).status()).isEqualTo("ARCHIVING");
    }

    @Test
    void staleOrdinaryRecoveryCandidateCannotAbortOrClaimArchiveOwnedBytes() {
        var scope = pendingArchiveScope();
        String key = objects.lastKey;
        var stale = publication(key);
        var service = archiveService();
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        due(key);
        UUID token = UUID.randomUUID();
        assertThatThrownBy(() -> catalog.recoverPublication(stale, token))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            var now = OffsetDateTime.now();
                            assertThat(publications.abort(org, user, stale.id(), now)).isZero();
                            assertThat(
                                            publications.deferRecovery(
                                                    org,
                                                    user,
                                                    stale.id(),
                                                    null,
                                                    now,
                                                    now.plusSeconds(30)))
                                    .isZero();
                            assertThat(
                                            publications.reclaimRecovery(
                                                    org,
                                                    user,
                                                    stale.id(),
                                                    token,
                                                    now,
                                                    now.plusSeconds(60),
                                                    3))
                                    .isZero();
                            assertThat(
                                            publications.abandonRecovery(
                                                    org, user, stale.id(), token, now))
                                    .isZero();
                        });
        assertThat(publication(key).status()).isEqualTo("STORED");
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(publication(key).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void archivingIsOptionalAndRejectsExpiredOriginalAdmission() {
        var scope = pendingArchiveScope();
        properties.getFileStore().setWorkspaceArchiveEnabled(false);
        assertThatThrownBy(
                        () ->
                                archiveService()
                                        .defer(
                                                scannedCheckpoint(scope),
                                                "{}",
                                                java.util.List.of(),
                                                false))
                .hasMessageContaining("UNAVAILABLE");
        properties.getFileStore().setWorkspaceArchiveEnabled(true);
        db().updateAttemptExpiry(
                        scope.execution().attemptId(), OffsetDateTime.now().minusSeconds(1));
        assertThatThrownBy(
                        () ->
                                archiveService()
                                        .defer(
                                                scannedCheckpoint(scope),
                                                "{}",
                                                java.util.List.of(),
                                                false))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(
                        durableLeases.succeed(
                                scope.execution().attemptId(), scope.execution().leaseOwner()))
                .isFalse();
    }

    private Scope pendingArchiveScope() {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        failArchiveWrite(scope, "outputs/receipt.txt");
        return scope;
    }

    private void failArchiveWrite(Scope scope, String path) {
        var faulty = mock(FileVersionRepository.class, delegatesTo(versions));
        doThrow(
                        new org.springframework.dao.TransientDataAccessResourceException(
                                "fixture archive commit lost"))
                .when(faulty)
                .save(any());
        assertThatThrownBy(() -> taskWrite(catalog(faulty), scope, path, Map.of()))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
    }

    private WorkspaceCheckpointContext scannedCheckpoint(Scope scope) {
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        int count = scope.manifest().fileCount();
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(
                        count, count, 0, 3L * count, true, java.util.List.of()));
        checkpoint.projectionSucceeded(count);
        return checkpoint;
    }

    @ParameterizedTest
    @ValueSource(strings = {"reply", "status"})
    void publishedFilesAndFullReplyConvergeAfterPostComputeSubmissionFailureWithoutReexecution(
            String stage) throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        var archiveService = archiveService();
        var calls = new AtomicInteger();
        String answer = "full computed answer " + "detail ".repeat(1000) + " preserved-end-marker";
        var agentRuntime = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(agentRuntime.call(
                        any(io.agentscope.core.message.Msg.class),
                        any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            calls.incrementAndGet();
                            var context =
                                    invocation.getArgument(
                                            1, io.agentscope.core.agent.RuntimeContext.class);
                            var checkpoint = context.get(WorkspaceCheckpointContext.class);
                            checkpoint.planned(scope.manifest());
                            checkpoint.recordFile(file);
                            checkpoint.projectionSucceeded(1);
                            return reactor.core.publisher.Mono.just(
                                    io.agentscope.core.message.Msg.builder()
                                            .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                                            .textContent(answer)
                                            .build());
                        });
        var restore = mock(WorkspaceCheckpointRestoreService.class);
        when(restore.prepare(any(), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        var replyPort = chats;
        if (stage.equals("reply")) {
            replyPort = mock(ChatPersistenceService.class, delegatesTo(chats));
            doThrow(new IllegalStateException("fixture final reply commit unavailable"))
                    .when(replyPort)
                    .saveAssistantMessageForRun(any(), any(), any(), any(), any());
        }
        properties.getSandbox().setEnabled(true);
        properties.getOrchestration().setWorkerResultSummaryMaxCharacters(100);
        var executor =
                new io.agentscope.saas.app.orchestration.HarnessDurableTaskExecutor(
                        agentRuntime,
                        mapper,
                        properties,
                        replyPort,
                        orchestration,
                        workspaceArtifacts,
                        restore,
                        java.util.Optional.of(
                                mock(
                                        io.agentscope.harness.agent.filesystem.remote.store
                                                .BaseStore.class)),
                        archiveService);
        var request =
                new io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest(
                        scope.execution().attemptId(),
                        scope.execution().leaseOwner(),
                        org,
                        scope.runId(),
                        scope.execution().taskId(),
                        user,
                        agent,
                        session,
                        scope.execution().agentRunId(),
                        "assistant",
                        null,
                        "member",
                        "standard",
                        2,
                        0,
                        "compute",
                        "{\"prompt\":\"compute\",\"_runtime\":{\"directChat\":true}}",
                        WorkspaceIsolationMode.ATTEMPT_ISOLATED);
        UUID job;
        if (stage.equals("reply")) {
            try {
                executor.execute(request);
                throw new AssertionError("Expected archive handover");
            } catch (WorkspaceArchiveDeferredException deferred) {
                job = deferred.jobId();
            }
        } else {
            var result = executor.execute(request);
            assertThat(result.outputJson()).doesNotContain("preserved-end-marker");
            try {
                result.commitFailed(
                        new IllegalStateException("fixture status submission unavailable"));
                throw new AssertionError("Expected archive handover");
            } catch (io.agentscope.saas.orchestration.TaskResultCommitException deferred) {
                job = ((WorkspaceArchiveDeferredException) deferred.getCause()).jobId();
            }
        }
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("ARCHIVING");
        assertThat(archiveService.recover(archive(job))).isTrue();
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("SUCCEEDED");
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
        String json = db().runMessageContents(scope.runId()).get(0);
        var tree = mapper.readTree(json);
        if (tree.isTextual()) tree = mapper.readTree(tree.asText());
        assertThat(tree.toString()).contains("preserved-end-marker");
        assertThat(calls).hasValue(1);
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(receipts(scope)).hasSize(1);
    }

    @Test
    void completionAcknowledgementQueryRequiresTheExactAttemptOwnerAndActualSuccess() {
        var scope = activeScope();
        assertThat(
                        durableLeases.completedSuccessfully(
                                scope.execution().attemptId(), scope.execution().leaseOwner()))
                .isFalse();
        assertThat(
                        durableLeases.succeed(
                                scope.execution().attemptId(),
                                scope.execution().leaseOwner(),
                                "{}"))
                .isTrue();
        assertThat(
                        durableLeases.completedSuccessfully(
                                scope.execution().attemptId(), scope.execution().leaseOwner()))
                .isTrue();
        assertThat(
                        durableLeases.completedSuccessfully(
                                scope.execution().attemptId(), "another-owner"))
                .isFalse();
        assertThat(
                        durableLeases.completedSuccessfully(
                                UUID.randomUUID(), scope.execution().leaseOwner()))
                .isFalse();
    }

    @Test
    void committedArchiveHandoverSurvivesALostTransactionAcknowledgement() {
        var scope = activeScope();
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        finishCheckpoint(scope, file);
        var lost = new AtomicBoolean();
        PlatformTransactionManager acknowledgements =
                new PlatformTransactionManager() {
                    @Override
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return manager.getTransaction(definition);
                    }

                    @Override
                    public void commit(org.springframework.transaction.TransactionStatus status) {
                        manager.commit(status);
                        if (lost.compareAndSet(false, true))
                            throw new IllegalStateException("fixture commit acknowledgement lost");
                    }

                    @Override
                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                        manager.rollback(status);
                    }
                };
        var bodies =
                new RuntimeBodyService(runtimeBodies, runtimeMessages, stores, properties, manager);
        var service =
                new WorkspaceArchiveService(
                        archives,
                        manifestRepository,
                        manifestService,
                        publications,
                        catalog,
                        bodies,
                        chats,
                        durableLeases,
                        sandboxLeases,
                        runs,
                        mapper,
                        properties,
                        acknowledgements);
        var checkpoint = boundCheckpoint(scope, file);
        UUID id = service.defer(checkpoint, "{}", java.util.List.of(), false);
        assertThat(lost).isTrue();
        assertThat(archive(id).status()).isEqualTo("PENDING");
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("SUCCEEDED");
    }

    private WorkspaceArchiveService archiveService() {
        return archiveService(null);
    }

    private WorkspaceArchiveService archiveService(WorkspaceSourceService sources) {
        var bodies =
                new RuntimeBodyService(runtimeBodies, runtimeMessages, stores, properties, manager);
        return new WorkspaceArchiveService(
                archives,
                manifestRepository,
                manifestService,
                publications,
                catalog,
                bodies,
                chats,
                durableLeases,
                sandboxLeases,
                runs,
                mapper,
                properties,
                manager,
                sources);
    }

    private WorkspaceSourceService sourceService() {
        return new WorkspaceSourceService(
                sourceRepository,
                manifestRepository,
                manifestService,
                archives,
                orgs,
                files,
                stores,
                properties,
                manager);
    }

    private record RetentionFixture(
            Scope scope,
            WorkspaceSourceService sources,
            io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend backend,
            ObjectProvider<io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend> backends,
            java.util.concurrent.atomic.AtomicReference<byte[]> capture,
            UUID sourceId) {}

    @SuppressWarnings("unchecked")
    private RetentionFixture retainProvider() throws Exception {
        return retainProvider(false);
    }

    @SuppressWarnings("unchecked")
    private RetentionFixture retainProvider(boolean derivedContext) throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        UUID resource = UUID.randomUUID();
        UUID tracking = UUID.randomUUID();
        db().insertMaintenanceSandbox(
                        tracking,
                        org,
                        user,
                        resource.toString(),
                        OffsetDateTime.now().plusHours(1));
        db().bindMaintenanceSandbox(tracking, agent, session.toString());
        var leaseService = new io.agentscope.saas.sandbox.SandboxLeaseService(sandboxLeases);
        var lease =
                new TransactionTemplate(manager)
                        .execute(
                                tx -> {
                                    var value =
                                            leaseService.begin(
                                                    org,
                                                    user,
                                                    scope.runId(),
                                                    scope.execution().taskId(),
                                                    scope.execution().attemptId(),
                                                    new io.agentscope.saas.sandbox
                                                            .ActiveSandboxDeployment(
                                                            "opensandbox",
                                                            "fixture",
                                                            java.util.Set.of(),
                                                            "{}"),
                                                    scope.execution().leaseOwner(),
                                                    OffsetDateTime.now().plusHours(1));
                                    assertThat(
                                                    leaseService.activate(
                                                            value,
                                                            resource.toString(),
                                                            "{}",
                                                            OffsetDateTime.now().plusHours(1)))
                                            .isTrue();
                                    return value;
                                });
        var sandbox = mock(io.agentscope.harness.agent.sandbox.Sandbox.class);
        var state = new io.agentscope.harness.agent.sandbox.SandboxState() {};
        state.setSessionId(resource.toString());
        when(sandbox.getState()).thenReturn(state);
        var context =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .sessionId(session.toString())
                        .build();
        context.put(io.agentscope.harness.agent.sandbox.Sandbox.class, sandbox);
        context.put(
                io.agentscope.saas.sandbox.SandboxTrackingContext.class,
                new io.agentscope.saas.sandbox.SandboxTrackingContext(tracking, org.toString()));
        context.put(io.agentscope.saas.sandbox.SandboxLeaseContext.class, lease);
        if (derivedContext) {
            context.put(
                    io.agentscope.saas.sandbox.SandboxTrackingContext.class,
                    (io.agentscope.saas.sandbox.SandboxTrackingContext) null);
            context.put(
                    io.agentscope.saas.sandbox.SandboxLeaseContext.class,
                    (io.agentscope.saas.sandbox.SandboxLeaseContext) null);
        }
        var backend = mock(io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.class);
        when(backend.providerId()).thenReturn("opensandbox");
        var captured = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            captured.set(java.nio.file.Files.readAllBytes(call.getArgument(2)));
                            return null;
                        })
                .when(backend)
                .seal(any(), any(), any(), any());
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            java.nio.file.Files.write(call.getArgument(2), captured.get());
                            return null;
                        })
                .when(backend)
                .retrieve(any(), any(), any(), anyLong());
        ObjectProvider<io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend> backends =
                mock(ObjectProvider.class);
        when(backends.getIfAvailable()).thenReturn(backend);
        var sources = sourceService();
        sources.configureRetention(retentionRepository, backends);
        var plan =
                new WorkspaceProjectionPlan(
                        java.util.List.of(
                                new WorkspaceProjectionPlan.Entry(
                                        "outputs/receipt.txt",
                                        "outputs/receipt.txt",
                                        3,
                                        digest("abc"),
                                        false)),
                        1,
                        3);
        var path = java.nio.file.Files.createTempFile("retention-fixture-", ".tar");
        try {
            try (var tar =
                    new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                            java.nio.file.Files.newOutputStream(path))) {
                var entry =
                        new org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                                "outputs/receipt.txt");
                entry.setSize(3);
                org.apache.commons.compress.archivers.ArchiveOutputStream compatible = tar;
                compatible.putArchiveEntry(entry);
                tar.write(bytes("abc"));
                tar.closeArchiveEntry();
                tar.finish();
            }
            objects.offline = true;
            assertThatThrownBy(() -> sources.capture(scope.manifest(), plan, path))
                    .hasMessageContaining("fixture object store offline");
            assertThat(sources.captureRetention(context, scope.manifest(), plan, path)).isTrue();
            var hold =
                    context.get(
                            io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend.Hold
                                    .class);
            assertThat(hold).isNotNull();
            return new RetentionFixture(
                    scope, sources, backend, backends, captured, hold.sourceId());
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }

    @Test
    void storeOutageSealsResultInPgAndRecoversOnlyFilesFromRetainedProvider() throws Exception {
        var fixture = retainProvider();
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(
                        scannedCheckpoint(fixture.scope()),
                        "{}",
                        java.util.List.of(TextBlock.builder().text("retained complete").build()),
                        true);
        var job = archive(id);
        assertThat(job.bodyId()).isNull();
        assertThat(archives.inlineResult(job).orElseThrow()).contains("retained complete");
        assertThat(db().archiveAttemptStatus(job.attemptId())).isEqualTo("ARCHIVING");
        assertThat(retentionRepository.due(OffsetDateTime.now(), 100))
                .noneMatch(r -> r.sourceId().equals(fixture.sourceId()));
        objects.offline = false;
        assertThat(service.recover(job)).isTrue();
        assertThat(catalog.readCurrentFile(tenant, "outputs/receipt.txt").orElseThrow().text())
                .isEqualTo("abc");
        assertThat(db().taskAttemptCount(job.taskId())).isEqualTo(1);
        assertThat(db().archiveAttemptStatus(job.attemptId())).isEqualTo("SUCCEEDED");
        new WorkspaceRetentionGcJob(retentionRepository, admin, fixture.backends())
                .collectOnce(OffsetDateTime.now());
        assertThat(retentionRepository.owned(org, user, fixture.sourceId()).orElseThrow().status())
                .isEqualTo("CLEANED");
        verify(fixture.backend()).terminate(any());
    }

    @Test
    void copiedReleaseContextResolvesExactProviderBindingFromPg() throws Exception {
        var fixture = retainProvider(true);
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(scannedCheckpoint(fixture.scope()), "{}", java.util.List.of(), false);
        objects.offline = false;
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(catalog.readCurrentFile(tenant, "outputs/receipt.txt").orElseThrow().text())
                .isEqualTo("abc");
    }

    @Test
    void mutatedProviderCaptureCannotPublishEvenOneFile() throws Exception {
        var fixture = retainProvider();
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(scannedCheckpoint(fixture.scope()), "{}", java.util.List.of(), false);
        fixture.capture().get()[512] ^= 1;
        objects.offline = false;
        assertThatThrownBy(() -> service.recover(archive(id)))
                .hasMessageContaining("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED");
        assertThat(receipts(fixture.scope())).isEmpty();
        assertThat(db().archiveAttemptStatus(fixture.scope().execution().attemptId()))
                .isEqualTo("ARCHIVING");
    }

    @Test
    void exceptionalPgResultRespectsBodyQuotaBeforeTaskHandover() throws Exception {
        var fixture = retainProvider();
        properties.getRuntimeArchive().setMaxBodyUserBytes(1);
        properties.getRuntimeArchive().setMaxBodyOrgBytes(1);
        assertThatThrownBy(
                        () ->
                                archiveService(fixture.sources())
                                        .defer(
                                                scannedCheckpoint(fixture.scope()),
                                                "{}",
                                                java.util.List.of(),
                                                false))
                .hasMessageContaining("RUNTIME_BODY_QUOTA_EXCEEDED");
        assertThat(db().archiveAttemptStatus(fixture.scope().execution().attemptId()))
                .isEqualTo("RUNNING");
    }

    @Test
    void exceptionalPgResultHasAOneMiBHardLimit() throws Exception {
        var fixture = retainProvider();
        assertThatThrownBy(
                        () ->
                                archiveService(fixture.sources())
                                        .defer(
                                                scannedCheckpoint(fixture.scope()),
                                                "{}",
                                                java.util.List.of(
                                                        TextBlock.builder()
                                                                .text("x".repeat(1048576))
                                                                .build()),
                                                true))
                .hasMessageContaining("WORKSPACE_ARCHIVE_INLINE_LIMIT");
        assertThat(db().archiveAttemptStatus(fixture.scope().execution().attemptId()))
                .isEqualTo("RUNNING");
    }

    @Test
    void completedInlineResultsAreCollectedWithoutDeletingEmployeeOutputOrReply() throws Exception {
        var fixture = retainProvider();
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(
                        scannedCheckpoint(fixture.scope()),
                        "{}",
                        java.util.List.of(TextBlock.builder().text("final answer").build()),
                        true);
        objects.offline = false;
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(archives.inlineBytes(org, user)).isPositive();
        var gc = new WorkspaceRetentionGcJob(retentionRepository, admin, fixture.backends());
        gc.configureResultCleanup(archives, properties);
        gc.collectOnce(OffsetDateTime.now());
        assertThat(archives.inlineResult(archive(id))).isPresent();
        gc.collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(archives.inlineResult(archive(id))).isEmpty();
        assertThat(archives.inlineBytes(org, user)).isZero();
        assertThat(archive(id).status()).isEqualTo("COMPLETED");
        assertThat(catalog.readCurrentFile(tenant, "outputs/receipt.txt").orElseThrow().text())
                .isEqualTo("abc");
    }

    @Test
    void resetRevokesRetainedArchiveAndAllowsCleanupWithoutPublishing() throws Exception {
        var fixture = retainProvider();
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(scannedCheckpoint(fixture.scope()), "{}", java.util.List.of(), false);
        db().advanceSessionGeneration(session);
        assertThat(service.recover(archive(id))).isFalse();
        assertThat(archive(id).status()).isEqualTo("CANCELLED");
        new WorkspaceRetentionGcJob(retentionRepository, admin, fixture.backends())
                .collectOnce(OffsetDateTime.now());
        assertThat(retentionRepository.owned(org, user, fixture.sourceId()).orElseThrow().status())
                .isEqualTo("CLEANED");
        assertThat(receipts(fixture.scope())).isEmpty();
    }

    @Test
    void expiredRetainedArchiveFailsExplicitlyAndReleasesOnlyItsResource() throws Exception {
        var fixture = retainProvider();
        var service = archiveService(fixture.sources());
        UUID id =
                service.defer(scannedCheckpoint(fixture.scope()), "{}", java.util.List.of(), false);
        db().expireArchiveDeadline(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(archive(id).status()).isEqualTo("FAILED");
        new WorkspaceRetentionGcJob(retentionRepository, admin, fixture.backends())
                .collectOnce(OffsetDateTime.now());
        assertThat(retentionRepository.owned(org, user, fixture.sourceId()).orElseThrow().status())
                .isEqualTo("CLEANED");
        assertThat(receipts(fixture.scope())).isEmpty();
    }

    private io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Source retainSource(
            Scope scope) throws Exception {
        return retainSource(scope, () -> {});
    }

    private io.agentscope.saas.domain.workspace.WorkspaceSourceRepository.Source retainSource(
            Scope scope, Runnable sealed) throws Exception {
        var entries = manifestRepository.entries(org, scope.manifest().id());
        var plan =
                new WorkspaceProjectionPlan(
                        entries.stream()
                                .map(
                                        e ->
                                                new WorkspaceProjectionPlan.Entry(
                                                        "/" + e.sourcePath(),
                                                        "/" + e.logicalPath(),
                                                        e.sizeBytes(),
                                                        e.sha256(),
                                                        e.unchanged()))
                                .toList(),
                        scope.manifest().scannedFiles(),
                        scope.manifest().totalBytes());
        var path = java.nio.file.Files.createTempFile("workspace-source-fixture-", ".tar");
        try {
            try (var tar =
                    new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                            java.nio.file.Files.newOutputStream(path))) {
                org.apache.commons.compress.archivers.ArchiveOutputStream compatible = tar;
                for (var e : entries) {
                    var entry =
                            new org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                                    e.sourcePath());
                    entry.setSize(3);
                    compatible.putArchiveEntry(entry);
                    tar.write(bytes("abc"));
                    tar.closeArchiveEntry();
                }
                tar.finish();
            }
            assertThat(sourceService().capture(scope.manifest(), plan, path, sealed)).isTrue();
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
        return new TransactionTemplate(manager)
                .execute(
                        tx ->
                                sourceRepository
                                        .lockOwned(org, user, scope.manifest().id())
                                        .orElseThrow());
    }

    private UUID prepareCompletion(Scope scope) throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var saved = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        retainSource(
                scope,
                () ->
                        saved.set(
                                archiveService(sourceService())
                                        .prepare(
                                                checkpoint,
                                                "{}",
                                                java.util.List.of(
                                                        TextBlock.builder()
                                                                .text("frozen final answer")
                                                                .build()),
                                                true)));
        return saved.get();
    }

    @Test
    void preparedCompletionIsAtomicWithSourceSealAndSuppressesExpiredComputeRetry()
            throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        var job = archive(id);
        assertThat(archives.isPrepared(job)).isTrue();
        assertThat(job.bodyId()).isNull();
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("RUNNING");
        db().activateAttemptLease(
                        scope.execution().attemptId(),
                        scope.execution().leaseOwner(),
                        OffsetDateTime.now().minusSeconds(1),
                        OffsetDateTime.now());
        assertThat(durableLeases.recoverExpired(1000)).isZero();
        assertThat(
                        taskLeaseRepository.finishAttempt(
                                scope.execution().attemptId(),
                                scope.execution().leaseOwner(),
                                "ABANDONED",
                                "WORKER_LEASE_EXPIRED",
                                "stale candidate",
                                OffsetDateTime.now(),
                                OffsetDateTime.now()))
                .isZero();
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(archiveService(sourceService()).recover(archive(id))).isTrue();
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("SUCCEEDED");
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
        assertThat(db().runMessageContents(scope.runId()).get(0)).contains("frozen final answer");
        assertThat(receipts(scope)).hasSize(1);
        assertThat(archives.isPrepared(archive(id))).isFalse();
    }

    @Test
    void liveOwnerKeepsNormalCompletionAndPreparedJobOnlySettlesMetadata() throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        var file = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        finishCheckpoint(scope, file);
        chats.saveAssistantMessageForRun(
                tenant,
                session,
                agent,
                scope.runId(),
                java.util.List.of(TextBlock.builder().text("normal final answer").build()));
        assertThat(
                        durableLeases.succeed(
                                scope.execution().attemptId(),
                                scope.execution().leaseOwner(),
                                "{}"))
                .isTrue();
        assertThat(archiveService(sourceService()).recover(archive(id))).isFalse();
        assertThat(archive(id).status()).isEqualTo("COMPLETED");
        assertThat(archives.isPrepared(archive(id))).isFalse();
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
        assertThat(db().runMessageContents(scope.runId()).get(0)).contains("normal final answer");
    }

    @Test
    void preparedTakeoverReusesFilesPublishedBeforeOwnerCrashed() throws Exception {
        var scope = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/second.txt"));
        UUID id = prepareCompletion(scope);
        taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(archiveService(sourceService()).recover(archive(id))).isTrue();
        assertThat(receipts(scope)).hasSize(2);
        assertThat(catalog.listVersions(tenant, "outputs/receipt.txt")).hasSize(1);
        assertThat(catalog.listVersions(tenant, "outputs/second.txt")).hasSize(1);
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
    }

    @Test
    void competingPreparedConsumersPublishOnlyOneReplyAndReceipt() throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        var candidate = archive(id);
        var service = archiveService(sourceService());
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> call =
                () -> {
                    TenantContextHolder.setOrgId(org.toString());
                    try {
                        start.await();
                        return service.recover(candidate);
                    } finally {
                        TenantContextHolder.clear();
                    }
                };
        var one = executor.submit(call);
        var two = executor.submit(call);
        start.countDown();
        boolean first = one.get(20, TimeUnit.SECONDS), second = two.get(20, TimeUnit.SECONDS);
        assertThat(first || second).isTrue();
        assertThat(archive(id).status()).isEqualTo("COMPLETED");
        assertThat(receipts(scope)).hasSize(1);
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void sourceSealAndPreparedResultRollbackTogether() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        assertThatThrownBy(
                        () ->
                                retainSource(
                                        scope,
                                        () -> {
                                            archiveService(sourceService())
                                                    .prepare(
                                                            checkpoint,
                                                            "{}",
                                                            java.util.List.of(),
                                                            false);
                                            throw new IllegalStateException(
                                                    "fixture seal commit failure");
                                        }))
                .hasMessage("fixture seal commit failure");
        assertThat(archives.findAttempt(scope.manifest().scope())).isEmpty();
        var source =
                new TransactionTemplate(manager)
                        .execute(
                                tx ->
                                        sourceRepository
                                                .lockOwned(org, user, scope.manifest().id())
                                                .orElseThrow());
        assertThat(source.status()).isEqualTo("STAGING");
        assertThat(db().workspaceManifestStatus(org, scope.manifest().id()))
                .isEqualTo("DISCOVERED");
    }

    @Test
    void preparedGraceAndExplicitDeferReuseTheExactFrozenResult() throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        var service = archiveService(sourceService());
        assertThat(service.recover(archive(id))).isFalse();
        int puts = objects.puts.get();
        assertThat(service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false))
                .isEqualTo(id);
        assertThat(objects.puts.get()).isEqualTo(puts);
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("ARCHIVING");
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(db().runMessageContents(scope.runId()).get(0)).contains("frozen final answer");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void revokedPreparedCompletionNeverPublishesOrDelivers() throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        db().advanceSessionGeneration(session);
        assertThat(archiveService(sourceService()).recover(archive(id))).isFalse();
        assertThat(archive(id).status()).isEqualTo("CANCELLED");
        assertThat(archives.isPrepared(archive(id))).isFalse();
        assertThat(receipts(scope)).isEmpty();
        assertThat(db().countRunMessages(scope.runId())).isZero();
    }

    @Test
    void expiredPreparedCompletionFailsWithoutCreatingAnotherAttempt() throws Exception {
        var scope = activeScope();
        UUID id = prepareCompletion(scope);
        db().expireArchiveDeadline(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(archiveService(sourceService()).recover(archive(id))).isTrue();
        assertThat(archive(id).status()).isEqualTo("FAILED");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().countRunMessages(scope.runId())).isZero();
    }

    @Test
    void disabledPreparedHandoverLeavesExistingSourceAndCompletionBehaviorAvailable()
            throws Exception {
        properties.getFileStore().setWorkspaceCompletionHandoverEnabled(false);
        var scope = activeScope();
        assertThat(prepareCompletion(scope)).isNull();
        assertThat(archives.findAttempt(scope.manifest().scope())).isEmpty();
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("RUNNING");
    }

    @Test
    void immutableEscrowRestoresAllMissingBytesAfterLocalCaptureWasDeleted() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/second.txt"));
        var source = retainSource(scope);
        assertThat(source.status()).isEqualTo("RETAINED");
        assertThat(source.objectKey())
                .startsWith("runtime/workspace-sources/")
                .doesNotStartWith("files/");
        assertThat(receipts(scope)).isEmpty();
        var service = archiveService(sourceService());
        UUID id =
                service.defer(
                        scannedCheckpoint(scope),
                        "{}",
                        java.util.List.of(TextBlock.builder().text("complete").build()),
                        true);
        assertThat(archives.items(archive(id))).allMatch(i -> i.publicationId() == null);
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(receipts(scope)).hasSize(2);
        assertThat(catalog.readCurrentFile(tenant, "outputs/second.txt").orElseThrow().text())
                .isEqualTo("abc");
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("SUCCEEDED");
        assertThat(db().countRunMessages(scope.runId())).isEqualTo(1);
        assertThat(archive(id).status()).isEqualTo("COMPLETED");
        var gc = new WorkspaceSourceGcJob(sourceRepository, admin, stores, properties);
        gc.collectOnce(OffsetDateTime.now().plusDays(2));
        assertThat(objects.data).doesNotContainKey(source.objectKey());
        assertThat(catalog.readCurrentFile(tenant, "outputs/second.txt").orElseThrow().text())
                .isEqualTo("abc");
    }

    @Test
    void failedRecoveryPutCanRetryFromEscrowWithoutDuplicatingReceipt() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        retainSource(scope);
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        objects.failPut = true;
        assertThatThrownBy(() -> service.recover(archive(id)))
                .hasMessage("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED");
        assertThat(receipts(scope)).isEmpty();
        objects.failPut = false;
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(receipts(scope)).hasSize(1);
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    @Test
    void corruptEscrowCannotPublishEvenOneFile() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var source = retainSource(scope);
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        byte[] original = objects.data.put(source.objectKey(), bytes("corrupt"));
        assertThatThrownBy(() -> service.recover(archive(id)))
                .hasMessage("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED");
        assertThat(receipts(scope)).isEmpty();
        objects.data.put(source.objectKey(), original);
        db().archiveDue(id, OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recover(archive(id))).isTrue();
    }

    @Test
    void escrowRecoveryNeverOverwritesNewerUserUpload() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        retainSource(scope);
        var uploaded = write("outputs/receipt.txt", "newer");
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        assertThatThrownBy(() -> service.recover(archive(id)))
                .hasMessage("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED");
        assertThat(receipts(scope)).isEmpty();
        assertThat(catalog.listVersions(tenant, "outputs/receipt.txt")).hasSize(1);
        assertThat(catalog.readCurrentFile(tenant, "outputs/receipt.txt").orElseThrow().text())
                .isEqualTo("newer");
    }

    @Test
    void sessionRevokedDuringSnapshotGetCannotPublishOutput() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var source = retainSource(scope);
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        objects.afterGet =
                () -> {
                    if (source.objectKey().equals(objects.lastReadKey))
                        db().advanceSessionGeneration(session);
                };
        assertThatThrownBy(() -> service.recover(archive(id)))
                .hasMessage("WORKSPACE_ARCHIVE_RECOVERY_DEFERRED");
        assertThat(receipts(scope)).isEmpty();
        assertThat(archive(id).status()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredOrCollectedSourceCannotAdmitMissingByteArchive() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var source = retainSource(scope);
        new WorkspaceSourceGcJob(sourceRepository, admin, stores, properties)
                .collectOnce(OffsetDateTime.now().plusDays(2));
        assertThat(objects.data).doesNotContainKey(source.objectKey());
        assertThatThrownBy(
                        () ->
                                archiveService(sourceService())
                                        .defer(
                                                scannedCheckpoint(scope),
                                                "{}",
                                                java.util.List.of(),
                                                false))
                .hasMessage("WORKSPACE_ARCHIVE_BYTES_MISSING");
        assertThat(db().archiveAttemptStatus(scope.execution().attemptId())).isEqualTo("RUNNING");
    }

    @Test
    void forgedArchiveTokenCannotPublishMissingBytes() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var source = retainSource(scope);
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        var baseline = sourceRepository.baselines(source).get(0);
        int puts = objects.puts.get();
        assertThatThrownBy(
                        () ->
                                catalog.recordWorkspaceFileForArchive(
                                        archive(id),
                                        new WorkspaceArchiveRepository.Authority(
                                                id, UUID.randomUUID()),
                                        baseline.logicalPath(),
                                        bytes("abc"),
                                        baseline))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(objects.puts.get()).isEqualTo(puts);
        assertThat(receipts(scope)).isEmpty();
    }

    @Test
    void escrowQuotaCountsOtherAttemptsAndFailedStages() throws Exception {
        var first = activeScope();
        var source = retainSource(first);
        properties.getFileStore().setWorkspaceSourceMaxBytes(source.sizeBytes());
        properties.getFileStore().setWorkspaceSourceMaxOrgBytes(source.sizeBytes());
        var next = activeScope();
        int puts = objects.puts.get();
        assertThatThrownBy(() -> retainSource(next)).hasMessage("WORKSPACE_SOURCE_QUOTA");
        assertThat(objects.puts.get()).isEqualTo(puts);
        assertThat(sourceRepository.reserved(org)).isEqualTo(source.sizeBytes());
    }

    private WorkspaceArchiveRepository.Job archive(UUID id) {
        return new TransactionTemplate(manager)
                .execute(tx -> archives.lockOwned(org, id).orElseThrow());
    }

    private WorkspaceInputService inputService() {
        properties.getSandbox().setEnabled(true);
        return new WorkspaceInputService(
                inputRepository, manifestRepository, stores, properties, manager);
    }

    private io.agentscope.harness.agent.sandbox.WorkspaceInputPlan loadInputs(Scope scope) {
        return inputService()
                .prepare(
                        tenant,
                        scope.runId(),
                        scope.execution().taskId(),
                        scope.execution().attemptId(),
                        scope.execution().leaseOwner())
                .orElseThrow();
    }

    private FileCatalogService.AttachmentRecord bindInput(
            Scope scope, FileCatalogService.FileRecord file) {
        return catalog.attachFile(
                        tenant,
                        agent,
                        session,
                        null,
                        scope.execution().taskId(),
                        file,
                        "user_input",
                        Map.of())
                .orElseThrow();
    }

    @Test
    void boundInputUsesOriginalVersionAndPathAfterOverwriteAndRename() {
        var original = write("inputs/pinned.txt", "old");
        var scope = activeScope();
        bindInput(scope, original);
        write("inputs/pinned.txt", "new");
        var entity =
                files.findByOrgIdAndUserIdAndLogicalPath(org, user, original.logicalPath())
                        .orElseThrow();
        entity.setLogicalPath("inputs/renamed.txt");
        files.save(entity);
        var plan = loadInputs(scope);
        assertThat(plan.workspace().files()).hasSize(1);
        assertThat(plan.workspace().files().get(0).path()).isEqualTo("inputs/pinned.txt");
        assertThat(new String(plan.workspace().files().get(0).content(), StandardCharsets.UTF_8))
                .isEqualTo("old");
        assertThat(inputRepository.bindings(scope.manifest().scope(), 10).get(0).versionId())
                .isEqualTo(original.versionId());
    }

    @Test
    void archivedOutputCanBeExplicitlyBoundAndRestoredWithoutMutableMirror() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var original = activeScope();
        retainSource(original);
        var archiveService = archiveService(sourceService());
        UUID id =
                archiveService.defer(scannedCheckpoint(original), "{}", java.util.List.of(), false);
        assertThat(archiveService.recover(archive(id))).isTrue();
        var scope = activeScope();
        var message = chats.saveUserMessage(tenant, session, agent, "use output");
        var version = catalog.listVersions(tenant, "outputs/receipt.txt").get(0);
        var attachment =
                catalog.attachExistingVersion(
                        tenant,
                        agent,
                        session,
                        message.getId(),
                        version.logicalPath(),
                        version.id());
        var stored =
                attachments
                        .findByOrgIdAndUserIdAndMessageIdOrderByCreatedAtDesc(
                                org, user, message.getId())
                        .get(0);
        stored.setTaskId(scope.execution().taskId());
        // Existing binding is already durable; select it as a root Run trigger, as the HTTP start
        // path does.
        db().setRunTrigger(scope.runId(), message.getId());
        assertThat(attachment.logicalPath()).isEqualTo("outputs/receipt.txt");
        var plan = loadInputs(scope);
        assertThat(plan.workspace().files().get(0).path()).isEqualTo("outputs/receipt.txt");
        assertThat(plan.workspace().files().get(0).content()).isEqualTo(bytes("abc"));
    }

    @Test
    void unselectedHistoryIsNeverReadByInputPreparation() {
        var selected = write("inputs/selected.txt", "one");
        write("outputs/history.txt", "other");
        var scope = activeScope();
        bindInput(scope, selected);
        objects.afterGet = () -> assertThat(objects.lastReadKey).isEqualTo(selected.objectKey());
        assertThat(loadInputs(scope).workspace().files()).hasSize(1);
    }

    @Test
    void emptyWorksetRemainsExplicitAndDoesNotPickUpLaterAttachments() {
        var scope = activeScope();
        var original = loadInputs(scope);
        assertThat(original.workspace().files()).isEmpty();
        bindInput(scope, write("inputs/later.txt", "new"));
        objects.lastReadKey = null;
        var recovered = loadInputs(scope);
        assertThat(recovered.workspace().files()).isEmpty();
        assertThat(recovered.workspace().workspaceVersion())
                .isEqualTo(original.workspace().workspaceVersion());
        assertThat(objects.lastReadKey).isNull();
    }

    @Test
    void freshInputServiceUsesFrozenVersionsAfterAttachmentsAreRemovedOrAdded() {
        var scope = activeScope();
        var old = write("inputs/pinned.txt", "old");
        var binding = bindInput(scope, old);
        var original = loadInputs(scope);
        db().removeInputAttachment(binding.id());
        bindInput(scope, write("inputs/new.txt", "new"));
        var recovered = loadInputs(scope);
        assertThat(recovered.workspace().workspaceVersion())
                .isEqualTo(original.workspace().workspaceVersion());
        assertThat(recovered.workspace().files()).hasSize(1);
        assertThat(recovered.workspace().files().get(0).path()).isEqualTo(old.logicalPath());
        assertThat(recovered.workspace().files().get(0).content()).isEqualTo(bytes("old"));
    }

    @Test
    void concurrentPreparersCommitOneIdenticalManifest() throws Exception {
        var scope = activeScope();
        bindInput(scope, write("inputs/pinned.txt", "old"));
        var readers = new CountDownLatch(2);
        objects.afterGet =
                () -> {
                    readers.countDown();
                    try {
                        assertThat(readers.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                };
        var versions = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var first = async(() -> versions.add(loadInputs(scope).workspace().workspaceVersion()));
        var second = async(() -> versions.add(loadInputs(scope).workspace().workspaceVersion()));
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(versions).hasSize(1);
        assertThat(inputRepository.frozenBindings(scope.manifest().scope(), 10)).hasSize(1);
    }

    @Test
    void deletingSessionCascadesWorksetEvidenceAndReleasesVersionPin() {
        var scope = activeScope();
        var old = write("inputs/pinned.txt", "old");
        var attachment = bindInput(scope, old);
        loadInputs(scope);
        db().removeInputAttachment(attachment.id());
        write("inputs/pinned.txt", "new");
        chats.deleteSession(session);
        assertThat(inputRepository.frozen(scope.manifest().scope())).isEmpty();
        assertThat(inputRepository.frozenBindings(scope.manifest().scope(), 10)).isEmpty();
        var gcMapper =
                MyBatisRepositoryTestSupport.mapper(
                        dataSource, io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper.class);
        assertThat(gcMapper.claimPrunableVersion(old.versionId())).isEqualTo(1);
    }

    @Test
    void frozenInputVersionIsPinnedAgainstPruningAndDeletionButDeletedFileCannotBeRead() {
        var scope = activeScope();
        var original = write("inputs/pinned.txt", "old");
        var binding = bindInput(scope, original);
        loadInputs(scope);
        db().removeInputAttachment(binding.id());
        write("inputs/pinned.txt", "new");
        var gcMapper =
                MyBatisRepositoryTestSupport.mapper(
                        dataSource, io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper.class);
        assertThat(gcMapper.findPrunableVersions(1, 100))
                .noneMatch(v -> v.id().equals(original.versionId()));
        assertThat(gcMapper.claimPrunableVersion(original.versionId())).isZero();
        catalog.markDeleted(tenant, original.logicalPath());
        assertThatThrownBy(() -> loadInputs(scope)).hasMessage("WORKSPACE_INPUT_BINDING_INVALID");
        assertThat(gcMapper.claimDeletedFile(original.fileId())).isZero();
    }

    @Test
    void dependencyArtifactsAreMountedByExactVersionAndFrozenAcrossServiceRebuilds() {
        var scope = activeScope();
        var file = write("outputs/report.txt", "old");
        UUID dependency = UUID.randomUUID();
        db().insertReadyTask(
                        dependency, org, scope.runId(), "dependency", "{}", OffsetDateTime.now());
        db().markTaskSucceeded(dependency, "{}");
        db().inputDependency(
                        UUID.randomUUID(),
                        org,
                        scope.runId(),
                        dependency,
                        scope.execution().taskId());
        artifacts.insert(
                new NewRunArtifact(
                        UUID.randomUUID(),
                        org,
                        scope.runId(),
                        dependency,
                        null,
                        file.fileId(),
                        file.versionId(),
                        file.logicalPath(),
                        "file",
                        "{}",
                        OffsetDateTime.now()));
        write("outputs/report.txt", "new");
        var gcMapper =
                MyBatisRepositoryTestSupport.mapper(
                        dataSource, io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper.class);
        assertThat(gcMapper.claimPrunableVersion(file.versionId())).isZero();
        assertThat(gcMapper.findPrunableVersions(1, 100))
                .noneMatch(v -> v.id().equals(file.versionId()));
        var plan = loadInputs(scope);
        assertThat(plan.workspace().files()).hasSize(1);
        assertThat(plan.workspace().files().get(0).path())
                .isEqualTo("inputs/dependencies/" + dependency + "/outputs/report.txt");
        assertThat(plan.workspace().files().get(0).content()).isEqualTo(bytes("old"));
        artifacts.insert(
                new NewRunArtifact(
                        UUID.randomUUID(),
                        org,
                        scope.runId(),
                        dependency,
                        null,
                        file.fileId(),
                        file.versionId(),
                        "outputs/later.txt",
                        "file",
                        "{}",
                        OffsetDateTime.now()));
        assertThat(loadInputs(scope).workspace().workspaceVersion())
                .isEqualTo(plan.workspace().workspaceVersion());
        assertThat(loadInputs(scope).workspace().files()).hasSize(1);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_USAGE_TEST", matches = "true")
    void postgresInputManifestRlsAndPrivilegesProtectFrozenEvidence() {
        var scope = activeScope();
        bindInput(scope, write("inputs/pinned.txt", "old"));
        loadInputs(scope);
        var inputScope = scope.manifest().scope();
        TenantContextHolder.setOrgId(UUID.randomUUID().toString());
        try {
            assertThat(inputRepository.frozen(inputScope)).isEmpty();
            assertThat(inputRepository.frozenBindings(inputScope, 10)).isEmpty();
        } finally {
            TenantContextHolder.setOrgId(org.toString());
        }
        assertThatThrownBy(() -> db().rewriteInputManifest(scope.execution().taskId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> db().removeInputEntries(scope.execution().taskId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(loadInputs(scope).workspace().files()).hasSize(1);
    }

    @Test
    void inputMetadataBudgetRejectsBeforeObjectRead() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        bindInput(scope, file);
        properties.getSandbox().setTransferMaxBytes(2);
        properties.getFileStore().setMaxFileBytes(2);
        objects.lastReadKey = null;
        assertThatThrownBy(() -> loadInputs(scope)).hasMessage("WORKSPACE_INPUT_LIMIT");
        assertThat(objects.lastReadKey).isNull();
    }

    @Test
    void corruptInputBytesFailBeforeSandboxAcquisition() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        bindInput(scope, file);
        objects.data.put(file.objectKey(), bytes("bad"));
        assertThatThrownBy(() -> loadInputs(scope)).hasMessage("WORKSPACE_INPUT_UNAVAILABLE");
    }

    @Test
    void sessionRevocationDuringInputGetRejectsThePlan() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        bindInput(scope, file);
        objects.afterGet = () -> db().advanceSessionGeneration(session);
        assertThatThrownBy(() -> loadInputs(scope))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
    }

    @Test
    void changedAttachmentDuringObjectGetRejectsOriginalPlan() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        var bound = bindInput(scope, file);
        objects.afterGet = () -> db().attachmentPath(bound.id(), "inputs/changed.txt");
        assertThatThrownBy(() -> loadInputs(scope)).hasMessage("WORKSPACE_INPUT_BINDINGS_CHANGED");
    }

    @Test
    void expiredInputOwnerCannotReadObjectBytes() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        bindInput(scope, file);
        db().updateAttemptExpiry(
                        scope.execution().attemptId(), OffsetDateTime.now().minusSeconds(1));
        objects.lastReadKey = null;
        assertThatThrownBy(() -> loadInputs(scope))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(objects.lastReadKey).isNull();
    }

    @Test
    void anotherEmployeeOrTaskCannotConsumeBoundInput() {
        var file = write("inputs/pinned.txt", "abc");
        var scope = activeScope();
        bindInput(scope, file);
        objects.lastReadKey = null;
        var stranger =
                new TenantContext(
                        org.toString(), UUID.randomUUID().toString(), "member", "standard", 2, 0);
        assertThatThrownBy(
                        () ->
                                inputService()
                                        .prepare(
                                                stranger,
                                                scope.runId(),
                                                scope.execution().taskId(),
                                                scope.execution().attemptId(),
                                                scope.execution().leaseOwner()))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThatThrownBy(
                        () ->
                                inputService()
                                        .prepare(
                                                tenant,
                                                scope.runId(),
                                                UUID.randomUUID(),
                                                scope.execution().attemptId(),
                                                scope.execution().leaseOwner()))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(objects.lastReadKey).isNull();
    }

    @Test
    void staleGcCandidateCannotCollectSourceAfterArchiveAdmission() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope();
        var source = retainSource(scope);
        var now = OffsetDateTime.now();
        db().sourceEligible(source.id(), now.minusSeconds(1));
        assertThat(sourceRepository.candidates(now, 100)).anyMatch(s -> s.id().equals(source.id()));
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        Integer claimed =
                admin.execute(
                        tx ->
                                sourceRepository.claim(
                                        source, UUID.randomUUID(), now, now.plusSeconds(120)));
        assertThat(claimed).isZero();
        assertThat(sourceRepository.candidates(now, 100))
                .noneMatch(s -> s.id().equals(source.id()));
        assertThat(objects.data).containsKey(source.objectKey());
        assertThat(service.recover(archive(id))).isTrue();
    }

    @Test
    void mixedPublishedAndMissingFilesUseSameManifestWithoutDuplicateArtifacts() throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        var scope = activeScope(java.util.List.of("outputs/receipt.txt", "outputs/second.txt"));
        retainSource(scope);
        var first = taskWrite(catalog, scope, "outputs/receipt.txt", Map.of());
        var service = archiveService(sourceService());
        UUID id = service.defer(scannedCheckpoint(scope), "{}", java.util.List.of(), false);
        assertThat(service.recover(archive(id))).isTrue();
        assertThat(receipts(scope)).hasSize(2);
        assertThat(catalog.listVersions(tenant, first.logicalPath())).hasSize(1);
        assertThat(receipts(scope)).anyMatch(r -> r.versionId().equals(first.versionId()));
        assertThat(db().taskAttemptCount(scope.execution().taskId())).isEqualTo(1);
    }

    private record Scope(UUID runId, Execution execution, SessionFence fence, Manifest manifest) {}

    private Scope activeScope() {
        return activeScope(java.util.List.of("outputs/receipt.txt"));
    }

    private Scope activeScope(java.util.List<String> paths) {
        var run =
                orchestration.createDirectRun(
                        tenant, agent, session, null, "receipt", "receipt-" + UUID.randomUUID());
        String owner = "receipt-owner-" + UUID.randomUUID();
        db().activateAttemptLease(
                        run.rootAttemptId(),
                        owner,
                        OffsetDateTime.now().plusHours(1),
                        OffsetDateTime.now());
        var execution =
                new Execution(run.rootTaskId(), run.rootAgentRunId(), run.rootAttemptId(), owner);
        var fence = runs.findCurrentSessionFence(run.runId(), org, user, agent).orElseThrow();
        var plan =
                new WorkspaceProjectionPlan(
                        paths.stream()
                                .map(
                                        path ->
                                                new WorkspaceProjectionPlan.Entry(
                                                        path, path, 3, digest("abc"), false))
                                .toList(),
                        paths.size(),
                        3L * paths.size());
        var manifest =
                manifestService.freeze(
                        new WorkspaceManifestRepository.Scope(
                                org,
                                user,
                                agent,
                                fence.sessionId(),
                                fence.generation(),
                                run.runId(),
                                execution),
                        plan);
        return new Scope(run.runId(), execution, fence, manifest);
    }

    private FileCatalogService.FileRecord taskWrite(
            FileCatalogService writer, Scope scope, String path, Map<String, Object> metadata) {
        var boundMetadata = new java.util.LinkedHashMap<String, Object>(metadata);
        boundMetadata.put("workspaceManifestId", scope.manifest().id().toString());
        return writer.recordWorkspaceFileForAttempt(
                        tenant,
                        agent,
                        scope.runId(),
                        scope.fence(),
                        scope.execution(),
                        path,
                        bytes("abc"),
                        "text/plain",
                        "test",
                        boundMetadata)
                .orElseThrow();
    }

    private java.util.List<FilePublicationRepository.ArtifactReceipt> receipts(Scope scope) {
        return publications.artifactReceipts(
                org, scope.runId(), scope.execution().taskId(), scope.execution().attemptId());
    }

    private WorkspaceArtifactService.Publication finishCheckpoint(
            Scope scope, FileCatalogService.FileRecord file) {
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(scope.manifest());
        checkpoint.recordFile(file);
        checkpoint.projectionSucceeded(1);
        manifestService.scanCompleted(
                scope.manifest(),
                new WorkspaceProjectionReport(1, 1, 0, 3, true, java.util.List.of()));
        return workspaceArtifacts.publish(
                org,
                scope.runId(),
                scope.execution().taskId(),
                scope.execution().attemptId(),
                null,
                checkpoint);
    }

    private TestDatabaseMapper db() {
        return MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
    }

    private String stageForRecovery(boolean rawWrite) throws Exception {
        properties.getFileStore().setPublicationRecoveryEnabled(true);
        String digest =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(bytes("abc")));
        UUID id = UUID.randomUUID();
        String key =
                "files/org="
                        + org
                        + "/user="
                        + user
                        + "/publications/"
                        + id
                        + "-"
                        + digest.substring(0, 16);
        OffsetDateTime until = OffsetDateTime.now().plusMinutes(5);
        var p =
                new Publication(
                        id,
                        org,
                        user,
                        agent,
                        null,
                        null,
                        null,
                        "outputs/stage.txt",
                        key,
                        "minio",
                        digest,
                        3,
                        3,
                        true,
                        "STAGED",
                        until,
                        until,
                        null,
                        0,
                        null);
        var intent =
                new Intent(
                        id,
                        null,
                        null,
                        null,
                        "text/plain",
                        "test",
                        "{}",
                        null,
                        null,
                        null,
                        null,
                        rawWrite);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            publications.stage(p);
                            publications.saveIntent(
                                    p, intent, true, OffsetDateTime.now().plusMinutes(15));
                        });
        objects.put(new FileObject(org, key, bytes("abc"), "text/plain", digest));
        due(key);
        return key;
    }

    private void due(String key) {
        MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class)
                .readyFilePublicationRecovery(id(key), OffsetDateTime.now().minusMinutes(1));
    }

    private FileCatalogService catalog(FileVersionRepository repository) {
        return catalog(repository, artifacts, runs, publications);
    }

    private FileCatalogService catalog(
            FileVersionRepository repository,
            RunArtifactRepository artifactPort,
            RunOrchestrationRepository eventPort,
            FilePublicationRepository publicationPort) {
        return catalog(repository, artifactPort, eventPort, publicationPort, manifestRepository);
    }

    private FileCatalogService catalog(
            FileVersionRepository repository,
            RunArtifactRepository artifactPort,
            RunOrchestrationRepository eventPort,
            FilePublicationRepository publicationPort,
            WorkspaceManifestRepository manifestPort) {
        return new FileCatalogService(
                files,
                repository,
                attachments,
                orgs,
                users,
                stores,
                mapper,
                properties,
                runs,
                new FilePublicationCoordinator(
                        publicationPort,
                        manager,
                        properties,
                        new FileArtifactReceiptService(
                                publicationPort, artifactPort, eventPort, mapper, manifestPort)));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes(value)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private FileCatalogService.FileRecord write(String path, String content) {
        return catalog.recordWorkspaceFile(
                        tenant, agent, null, path, bytes(content), "text/plain", "test", Map.of())
                .orElseThrow();
    }

    private Publication publication(String key) {
        return new TransactionTemplate(manager)
                .execute(tx -> publications.lockOwned(org, user, id(key)).orElseThrow());
    }

    private static UUID id(String key) {
        return UUID.fromString(key.substring(key.lastIndexOf('/') + 1, key.lastIndexOf('/') + 37));
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private Future<?> async(Runnable action) {
        return executor.submit(
                () -> {
                    TenantContextHolder.setOrgId(org.toString());
                    try {
                        action.run();
                    } finally {
                        TenantContextHolder.clear();
                    }
                });
    }

    private static class RecordingStore implements FileObjectStore {
        final Map<String, byte[]> data = new ConcurrentHashMap<>();
        final AtomicInteger puts = new AtomicInteger();
        final AtomicBoolean blocked = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile String lastKey;
        volatile String lastReadKey;
        boolean blockFirst, failPut, corrupt, offline;
        Runnable afterGet;

        @Override
        public String backend() {
            return "minio";
        }

        @Override
        public void put(FileObject object) throws Exception {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (offline) throw new IllegalStateException("fixture object store offline");
            puts.incrementAndGet();
            lastKey = object.objectKey();
            if (blockFirst && blocked.compareAndSet(false, true)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("fixture timeout");
            }
            data.put(object.objectKey(), object.content().clone());
            if (failPut) throw new IllegalStateException("response lost after upload");
        }

        @Override
        public byte[] get(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (offline) throw new IllegalStateException("fixture object store offline");
            lastReadKey = key;
            if (afterGet != null) afterGet.run();
            return corrupt ? bytes("wrong") : data.get(key).clone();
        }

        @Override
        public void delete(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            data.remove(key);
        }
    }
}
