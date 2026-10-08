/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import io.agentscope.saas.storage.MinioFileObjectStoreFactory;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "saas.runtime-archive.body-gc-enabled=false")
@ActiveProfiles("local")
class RuntimeBodyIntegrationTest {
    @Autowired RuntimeMessageRepository messages;
    @Autowired RuntimeBodyRepository bodies;
    @Autowired RunOrchestrationRepository runs;
    @Autowired FileObjectStore configuredStore;

    @Autowired
    @Qualifier("dataSource")
    DataSource dataSource;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager manager;

    @Autowired
    @Qualifier("adminTransactionOperations")
    TransactionOperations admin;

    private TestDatabaseMapper database;
    private UUID org, user, agent, session;
    private Scope scope;
    private RuntimeContext context;
    private TenantContext tenant;
    private SaasProperties properties;
    private RecordingStore store;
    private RuntimeBodyService bodyService;
    private PgSessionArchiveStore archive;

    @BeforeEach
    void seed() {
        database = MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = UUID.randomUUID();
        session = UUID.randomUUID();
        TenantContextHolder.setOrgId(org.toString());
        database.insertInvocationOrg(org, "body-" + org);
        database.insertInvocationUser(user, org, user + "@body.test");
        database.insertRuntimeArchiveAgent(agent, org, user);
        database.insertRuntimeArchiveSession(session, org, user, agent);
        scope = new Scope(org, user, agent, session, "assistant", session.toString());
        tenant = new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 0);
        context =
                RuntimeContext.builder()
                        .userId(user.toString())
                        .sessionId(session.toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, agent.toString())
                        .build();
        properties = new SaasProperties();
        properties.getRuntimeArchive().setInlineMaxBytes(256);
        store = new RecordingStore();
        bodyService = service(store);
        archive = new PgSessionArchiveStore(messages, runs, properties, manager, bodyService);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void largeSourceIsIsolatedAndRepeatedAdmissionDoesNotReupload() {
        Msg source = source("large", "body-prefix-" + "x".repeat(8000));
        archive.append(context, "assistant", scope.sessionKey(), List.of(source));
        for (int i = 0; i < 20; i++)
            assertThat(
                            archive.append(
                                            context,
                                            "assistant",
                                            scope.sessionKey(),
                                            List.of(source))
                                    .appended())
                    .isZero();
        var row = rows().get(0);
        assertThat(row.bodyId()).isNotNull();
        assertThat(row.payloadJson().length()).isLessThan(16000);
        assertThat(row.payloadJson()).doesNotContain("x".repeat(3000));
        assertThat(row.contentBytes())
                .isEqualTo(
                        SessionArchiveStore.payload(source)
                                .getBytes(StandardCharsets.UTF_8)
                                .length);
        assertThat(store.puts.get()).isEqualTo(1);
        assertThat(body(row).objectKey()).startsWith(RuntimeBodyService.prefix(scope));
        assertThat(
                        archive.readWindow(context, "assistant", scope.sessionKey(), 0L, null, 10)
                                .get(0)
                                .message()
                                .getTextContent())
                .isEqualTo(source.getTextContent());
    }

    @Test
    void previewPageDoesNotFetchObjectAndMarksTruncation() {
        archive.append(
                context,
                "assistant",
                scope.sessionKey(),
                List.of(source("large", "a".repeat(9000))));
        int gets = store.gets.get();
        var page =
                archive.page(
                        tenant,
                        agent,
                        session,
                        "assistant",
                        scope.sessionKey(),
                        0L,
                        null,
                        10,
                        false);
        assertThat(store.gets.get()).isEqualTo(gets);
        assertThat(page.items().get(0).message().getMetadata())
                .containsEntry("archiveBodyOffloaded", true);
        assertThat(page.items().get(0).message().getTextContent())
                .contains("Archived body preview")
                .hasSizeLessThan(2300);
    }

    @Test
    void corruptionAndMissingBytesAreNeverReturnedAsCompleteSource() {
        archive.append(
                context,
                "assistant",
                scope.sessionKey(),
                List.of(source("large", "a".repeat(4000))));
        var body = body(rows().get(0));
        byte[] original = store.objects.get(body.objectKey());
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 5] ^= 1;
        store.objects.put(body.objectKey(), corrupt);
        assertThatThrownBy(
                        () ->
                                archive.readWindow(
                                        context, "assistant", scope.sessionKey(), 0L, null, 10))
                .hasMessage("RUNTIME_BODY_READ_FAILED");
        store.objects.remove(body.objectKey());
        assertThatThrownBy(
                        () ->
                                archive.readWindow(
                                        context, "assistant", scope.sessionKey(), 0L, null, 10))
                .hasMessage("RUNTIME_BODY_READ_FAILED");
    }

    @Test
    void failedUploadDoesNotAllocateMessageSequence() {
        store.failPut = true;
        assertThatThrownBy(
                        () ->
                                archive.append(
                                        context,
                                        "assistant",
                                        scope.sessionKey(),
                                        List.of(source("large", "a".repeat(4000)))))
                .hasMessage("RUNTIME_BODY_WRITE_FAILED");
        assertThat(rows()).isEmpty();
        store.failPut = false;
        assertThat(
                        archive.append(
                                        context,
                                        "assistant",
                                        scope.sessionKey(),
                                        List.of(source("small", "ok")))
                                .lastSeq())
                .isEqualTo(1);
    }

    @Test
    void messageRollbackLeavesRecoverableOrphanThatGcDeletes() {
        var failed = mock(RuntimeMessageRepository.class, delegatesTo(messages));
        doThrow(new IllegalStateException("fixture insert failure"))
                .when(failed)
                .insert(any(), any());
        var failingArchive =
                new PgSessionArchiveStore(failed, runs, properties, manager, bodyService);
        assertThatThrownBy(
                        () ->
                                failingArchive.append(
                                        context,
                                        "assistant",
                                        scope.sessionKey(),
                                        List.of(source("large", "a".repeat(4000)))))
                .hasMessage("fixture insert failure");
        assertThat(rows()).isEmpty();
        assertThat(store.objects).hasSize(1);
        String key = store.objects.keySet().iterator().next();
        gc(store).collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).doesNotContainKey(key);
    }

    @Test
    void childReferenceKeepsSharedBodyAliveAndResetMakesItCollectable() {
        Msg original = source("parent", "a".repeat(4000));
        archive.append(context, "assistant", scope.sessionKey(), List.of(original));
        var body = body(rows().get(0));
        Msg projection =
                SessionArchiveStore.projection(
                        original,
                        original.withContent(List.of(TextBlock.builder().text("preview").build())));
        archive.append(context, "child", scope.sessionKey(), List.of(projection));
        assertThat(bodies.references(body.id())).isEqualTo(2);
        gc(store).collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).containsKey(body.objectKey());
        assertThat(store.puts.get()).isEqualTo(1);
        new TransactionTemplate(manager)
                .executeWithoutResult(tx -> messages.deleteSession(org, user, session));
        gc(store).collectOnce(OffsetDateTime.now().plusHours(2));
        assertThat(store.objects).doesNotContainKey(body.objectKey());
        assertThat(bodies.findOwned(scope, body.id()).orElseThrow().status()).isEqualTo("DELETED");
    }

    @Test
    void staleGcTokenAndLatePublicationAreFenced() {
        UUID id =
                bodyService.stage(
                        scope, SessionArchiveStore.payload(source("large", "a".repeat(4000))));
        var now = OffsetDateTime.now().plusHours(2);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertThat(bodies.claim(id, first, now, now.plusSeconds(1), 10)).isEqualTo(1);
        Integer attached =
                new TransactionTemplate(manager)
                        .execute(tx -> bodies.attach(scope, id, now.plusHours(1)));
        assertThat(attached).isZero();
        assertThat(bodies.claim(id, second, now.plusSeconds(2), now.plusSeconds(20), 10))
                .isEqualTo(1);
        assertThat(bodies.collected(id, first, now.plusDays(1))).isZero();
        assertThat(bodies.collected(id, second, now.plusDays(1))).isEqualTo(1);
        assertThat(bodies.ready(scope, id, now, now.plusDays(2))).isZero();
    }

    @Test
    void tombstoneRecheckDeletesLateFinishingUpload() {
        UUID id =
                bodyService.stage(
                        scope, SessionArchiveStore.payload(source("large", "a".repeat(4000))));
        var body = bodies.findOwned(scope, id).orElseThrow();
        byte[] bytes = store.objects.get(body.objectKey());
        var now = OffsetDateTime.now().plusHours(2);
        gc(store).collectOnce(now);
        assertThat(store.objects).doesNotContainKey(body.objectKey());
        store.objects.put(body.objectKey(), bytes);
        gc(store).collectOnce(now.plusDays(1).plusSeconds(1));
        assertThat(store.objects).doesNotContainKey(body.objectKey());
        assertThat(bodies.usage(org, user)).isZero();
    }

    @Test
    void employeeQuotaIncludesReservedUnpublishedBodies() {
        String json = SessionArchiveStore.payload(source("large", "a".repeat(4000)));
        properties
                .getRuntimeArchive()
                .setMaxBodyUserBytes(json.getBytes(StandardCharsets.UTF_8).length * 2L - 1);
        bodyService.stage(scope, json);
        assertThatThrownBy(() -> bodyService.stage(scope, json))
                .hasMessage("RUNTIME_BODY_QUOTA_EXCEEDED");
        assertThat(store.puts.get()).isEqualTo(1);
    }

    @Test
    void foreignOwnerCannotHydrateBody() {
        UUID id =
                bodyService.stage(
                        scope, SessionArchiveStore.payload(source("large", "a".repeat(4000))));
        Scope foreign =
                new Scope(org, UUID.randomUUID(), agent, session, "assistant", scope.sessionKey());
        int gets = store.gets.get();
        assertThatThrownBy(() -> bodyService.read(foreign, id, 4200))
                .hasMessage("RUNTIME_BODY_UNAVAILABLE");
        assertThat(store.gets.get()).isEqualTo(gets);
    }

    @Test
    void organizationQuotaIsSharedAcrossEmployees() {
        String json = SessionArchiveStore.payload(source("large", "a".repeat(4000)));
        long size = json.getBytes(StandardCharsets.UTF_8).length;
        properties.getRuntimeArchive().setMaxBodyUserBytes(size);
        properties.getRuntimeArchive().setMaxBodyOrgBytes(size * 2 - 1);
        bodyService.stage(scope, json);
        UUID otherUser = UUID.randomUUID(),
                otherAgent = UUID.randomUUID(),
                otherSession = UUID.randomUUID();
        database.insertInvocationUser(otherUser, org, otherUser + "@body.test");
        database.insertRuntimeArchiveAgent(otherAgent, org, otherUser);
        database.insertRuntimeArchiveSession(otherSession, org, otherUser, otherAgent);
        Scope other =
                new Scope(
                        org,
                        otherUser,
                        otherAgent,
                        otherSession,
                        "assistant",
                        otherSession.toString());
        assertThatThrownBy(() -> bodyService.stage(other, json))
                .hasMessage("RUNTIME_BODY_QUOTA_EXCEEDED");
        assertThat(store.puts.get()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateAdmissionHasOnlyOnePublishedBodyReference() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Msg source = source("large", "a".repeat(4000));
        try {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return archive.append(
                                        context, "assistant", scope.sessionKey(), List.of(source));
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return archive.append(
                                        context, "assistant", scope.sessionKey(), List.of(source));
                            });
            start.countDown();
            assertThat(
                            one.get(20, TimeUnit.SECONDS).appended()
                                    + two.get(20, TimeUnit.SECONDS).appended())
                    .isEqualTo(1);
            assertThat(rows()).hasSize(1);
            String published = body(rows().get(0)).objectKey();
            gc(store).collectOnce(OffsetDateTime.now().plusHours(2));
            assertThat(store.objects).containsOnlyKeys(published);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pgFallbackRoundTripRejectsOversizedRead() {
        assertThat(configuredStore.backend()).isEqualTo("pg");
        var realArchive =
                new PgSessionArchiveStore(
                        messages, runs, properties, manager, service(configuredStore));
        Msg source = source("pg-body", "pg-fallback-" + "x".repeat(6000));
        try {
            realArchive.append(context, "assistant", scope.sessionKey(), List.of(source));
            var body = body(rows().get(0));
            assertThatThrownBy(
                            () ->
                                    configuredStore.getBounded(
                                            org, body.objectKey(), body.sizeBytes() - 1))
                    .isInstanceOf(java.io.FileNotFoundException.class);
            assertThat(
                            realArchive
                                    .readWindow(
                                            context, "assistant", scope.sessionKey(), 0L, null, 1)
                                    .get(0)
                                    .message()
                                    .getTextContent())
                    .isEqualTo(source.getTextContent());
        } finally {
            new TransactionTemplate(manager)
                    .executeWithoutResult(tx -> messages.deleteSession(org, user, session));
            gc(configuredStore).collectOnce(OffsetDateTime.now().plusHours(2));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SAAS_RUNTIME_BODY_MINIO_TEST", matches = "true")
    void actualMinioRoundTripAndGc() {
        FileObjectStore minio =
                MinioFileObjectStoreFactory.create(
                        "http://localhost:9000",
                        "minioadmin",
                        "minioadmin",
                        null,
                        "agentscope-runtime-body-test");
        var realArchive =
                new PgSessionArchiveStore(messages, runs, properties, manager, service(minio));
        Msg source = source("minio", "actual-minio-" + "x".repeat(500000));
        try {
            realArchive.append(context, "assistant", scope.sessionKey(), List.of(source));
            var row = rows().get(0);
            assertThat(body(row).backend()).isEqualTo("minio");
            assertThatThrownBy(
                            () ->
                                    minio.getBounded(
                                            org, body(row).objectKey(), row.contentBytes() - 1))
                    .hasMessage("Object exceeds read budget");
            assertThat(
                            realArchive
                                    .readWindow(
                                            context, "assistant", scope.sessionKey(), 0L, null, 1)
                                    .get(0)
                                    .message()
                                    .getTextContent())
                    .isEqualTo(source.getTextContent());
        } finally {
            new TransactionTemplate(manager)
                    .executeWithoutResult(tx -> messages.deleteSession(org, user, session));
            gc(minio).collectOnce(OffsetDateTime.now().plusHours(2));
        }
    }

    private RuntimeBodyService service(FileObjectStore selected) {
        return new RuntimeBodyService(bodies, messages, provider(selected), properties, manager);
    }

    private RuntimeBodyGcJob gc(FileObjectStore selected) {
        return new RuntimeBodyGcJob(bodies, admin, provider(selected), properties);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<FileObjectStore> provider(FileObjectStore selected) {
        ObjectProvider<FileObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(selected);
        return provider;
    }

    private List<RuntimeMessageRepository.Message> rows() {
        return messages.findStream(scope)
                .map(stream -> messages.window(scope, stream.id(), 0L, null, 100, 33554432))
                .orElseGet(List::of);
    }

    private RuntimeBodyRepository.Body body(RuntimeMessageRepository.Message row) {
        return bodies.findOwned(scope, row.bodyId()).orElseThrow();
    }

    private static Msg source(String id, String text) {
        return Msg.builder().id(id).role(MsgRole.USER).name("user").textContent(text).build();
    }

    private static class RecordingStore implements FileObjectStore {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger puts = new AtomicInteger(), gets = new AtomicInteger();
        volatile boolean failPut;

        @Override
        public String backend() {
            return "pg";
        }

        @Override
        public void put(FileObject object) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            puts.incrementAndGet();
            if (failPut) throw new IllegalStateException("fixture store unavailable");
            objects.put(
                    object.objectKey(), Arrays.copyOf(object.content(), object.content().length));
        }

        @Override
        public byte[] get(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            gets.incrementAndGet();
            byte[] bytes = objects.get(key);
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void delete(UUID org, String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            objects.remove(key);
        }
    }
}
