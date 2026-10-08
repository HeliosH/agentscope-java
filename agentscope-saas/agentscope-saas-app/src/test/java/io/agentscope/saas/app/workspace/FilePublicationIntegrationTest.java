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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.domain.repository.OrgRepository;
import io.agentscope.saas.domain.repository.UserRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
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
    @Autowired ChatPersistenceService chats;
    @Autowired RunOrchestrationService orchestration;
    @Autowired ObjectMapper mapper;

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
                new FilePublicationCoordinator(publications, manager, properties));
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
        boolean blockFirst, failPut, corrupt;

        @Override
        public String backend() {
            return "minio";
        }

        @Override
        public void put(FileObject object) throws Exception {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
            return corrupt ? bytes("wrong") : data.get(key).clone();
        }

        @Override
        public void delete(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            data.remove(key);
        }
    }
}
