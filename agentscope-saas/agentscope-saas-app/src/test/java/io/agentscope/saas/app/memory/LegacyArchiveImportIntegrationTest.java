/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.support.MyBatisRepositoryTestSupport;
import io.agentscope.saas.app.support.TestDatabaseMapper;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Owner;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import io.agentscope.saas.domain.repository.FileVersionRepository;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "saas.runtime-archive.legacy-import.enabled=true",
            "saas.runtime-archive.legacy-import.max-batch-lines=2"
        })
@ActiveProfiles("local")
class LegacyArchiveImportIntegrationTest {
    @Autowired LegacyArchiveImportService service;
    @Autowired LegacyArchiveImportRepository jobs;
    @Autowired RuntimeMessageRepository messages;
    @Autowired ChatSessionRepository sessions;
    @Autowired io.agentscope.saas.domain.orchestration.RunOrchestrationRepository runs;
    @Autowired io.agentscope.saas.app.chat.ChatPersistenceService chats;
    @Autowired FileVersionRepository versions;
    @Autowired FileCatalogService files;
    @Autowired PgSessionArchiveStore archive;
    @Autowired WorkspaceTransferResources transfers;
    @Autowired SaasProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    private Owner owner;
    private TenantContext tenant;

    @BeforeEach
    void seed() {
        owner =
                new Owner(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        tenant =
                new TenantContext(
                        owner.orgId().toString(),
                        owner.userId().toString(),
                        "member",
                        "standard",
                        2,
                        0);
        TenantContextHolder.setOrgId(owner.orgId().toString());
        TestDatabaseMapper db =
                MyBatisRepositoryTestSupport.mapper(dataSource, TestDatabaseMapper.class);
        db.insertInvocationOrg(owner.orgId(), "legacy-" + owner.orgId());
        db.insertInvocationUser(owner.userId(), owner.orgId(), owner.userId() + "@legacy.test");
        db.insertRuntimeArchiveAgent(owner.agentId(), owner.orgId(), owner.userId());
        db.insertRuntimeArchiveSession(
                owner.sessionId(), owner.orgId(), owner.userId(), owner.agentId());
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    private Job create(String text) {
        var record =
                files.recordWorkspaceFile(
                                tenant,
                                owner.agentId(),
                                owner.sessionId(),
                                "agents/"
                                        + owner.agentId()
                                        + "/sessions/"
                                        + UUID.randomUUID()
                                        + ".log.jsonl",
                                text.getBytes(StandardCharsets.UTF_8),
                                "application/x-ndjson",
                                FileCatalogService.SOURCE_WORKSPACE_WRITE,
                                java.util.Map.of())
                        .orElseThrow();
        return service.create(owner, record.versionId());
    }

    private String entry(String id, String content) {
        return "{\"type\":\"message\",\"role\":\"USER\",\"content\":\""
                + content
                + "\""
                + (id == null ? "" : ",\"id\":\"" + id + "\"")
                + "}\n";
    }

    private List<SessionArchiveStore.Entry> history(Job job) {
        var rc =
                RuntimeContext.builder()
                        .userId(owner.userId().toString())
                        .sessionId(owner.sessionId().toString())
                        .put(TenantContext.ATTR_KEY, tenant)
                        .put(SandboxRuntimeAttributes.ATTR_AGENT_ID, owner.agentId().toString())
                        .build();
        return archive.readWindow(
                rc, LegacyArchiveImportService.LABEL, job.archiveKey(), 0L, null, 100);
    }

    @Test
    void boundedConversionKeepsMissingIdsRepeatedTextAndSkippedEvidence() {
        String source =
                entry("a", "same")
                        + entry("b", "same")
                        + entry(null, "same")
                        + "{\"type\":\"summary\",\"id\":\"sum\",\"summary\":\"old summary\"}\n"
                        + "{\"type\":\"message\",\"role\":\"TOOL\",\"id\":\"tool\",\"parentId\":\"b\",\"toolCallId\":\"call-1\",\"content\":\"rendered"
                        + " tool\"}\n"
                        + "{\"type\":\"message\",\"role\":\"SYSTEM\",\"content\":\"system"
                        + " prompt\"}\n";
        Job job = create(source);
        assertThat(service.create(owner, job.sourceVersionId()).id()).isEqualTo(job.id());
        Job first = service.advance(owner, job.id(), 0);
        assertThat(first.lineNo()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("READY");
        assertThatThrownBy(() -> service.advance(owner, job.id(), 0))
                .isInstanceOf(ResponseStatusException.class);
        Job second = service.advance(owner, job.id(), first.byteOffset());
        Job finalJob = service.advance(owner, job.id(), second.byteOffset());
        assertThat(finalJob.status()).isEqualTo("COMPLETE");
        assertThat(finalJob.byteOffset()).isEqualTo(source.getBytes(StandardCharsets.UTF_8).length);
        assertThat(finalJob.importedMessages()).isEqualTo(4);
        assertThat(finalJob.skippedEntries()).isEqualTo(2);
        var provenance = service.mappings(owner, job.id(), 0, 100);
        assertThat(provenance).hasSize(6);
        assertThat(provenance.get(2).syntheticIdentity()).isTrue();
        assertThat(provenance.get(4).toolCallId()).isEqualTo("call-1");
        assertThat(provenance.get(4).parentEntryId()).isEqualTo("b");
        assertThat(provenance.get(5).messageId()).isNull();
        assertThat(service.mappings(owner, job.id(), 3, 2))
                .extracting(m -> m.lineNo())
                .containsExactly(4L, 5L);
        var history = history(job);
        assertThat(history).hasSize(4);
        assertThat(history).extracting(e -> e.message().getId()).doesNotHaveDuplicates();
        assertThat(history.subList(0, 3))
                .extracting(e -> e.message().getTextContent())
                .containsExactly("same", "same", "same");
        assertThat(history)
                .allMatch(
                        e ->
                                e.message().getContent().stream()
                                        .noneMatch(
                                                b ->
                                                        b
                                                                instanceof
                                                                io.agentscope.core.message
                                                                        .ToolUseBlock));
        assertThat(service.advance(owner, job.id(), finalJob.byteOffset())).isEqualTo(finalJob);
        assertThat(files.describeVersion(tenant, job.sourceVersionId()).orElseThrow().sizeBytes())
                .isEqualTo(finalJob.sourceBytes());
    }

    @Test
    void sourceCommitWithoutProgressCanBeRebuiltWithoutDuplicateMessages() {
        Job job = create(entry("a", "first") + entry("b", "second"));
        LegacyArchiveImportRepository crashing =
                mock(LegacyArchiveImportRepository.class, AdditionalAnswers.delegatesTo(jobs));
        doThrow(new IllegalStateException("injected progress outage"))
                .when(crashing)
                .advance(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyBoolean());
        var firstProcess = service(crashing, files);
        assertThatThrownBy(() -> firstProcess.advance(owner, job.id(), 0))
                .hasMessage("injected progress outage");
        assertThat(history(job)).hasSize(2);
        assertThat(jobs.mappings(owner, job.id(), 0, 100)).isEmpty();
        assertThat(service.get(owner, job.id()).byteOffset()).isZero();
        var freshProcess = service(jobs, files);
        assertThat(freshProcess.advance(owner, job.id(), 0).status()).isEqualTo("COMPLETE");
        assertThat(history(job)).hasSize(2);
        assertThat(jobs.mappings(owner, job.id(), 0, 100)).hasSize(2);
    }

    @Test
    void simultaneousBatchesOnlyCommitOneProgressRange() throws Exception {
        Job job = create(entry("a", "first") + entry("b", "second") + entry("c", "third"));
        var barrier = new CyclicBarrier(2);
        FileCatalogService concurrent = mock(FileCatalogService.class);
        when(concurrent.describeVersion(any(), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            barrier.await(10, TimeUnit.SECONDS);
                            return files.describeVersion(call.getArgument(0), call.getArgument(1));
                        });
        var worker = service(jobs, concurrent);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> worker.advance(owner, job.id(), 0));
            var two = pool.submit(() -> worker.advance(owner, job.id(), 0));
            assertThat(one.get(20, TimeUnit.SECONDS).lineNo()).isEqualTo(2);
            assertThat(two.get(20, TimeUnit.SECONDS).lineNo()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(history(job)).hasSize(2);
        assertThat(jobs.mappings(owner, job.id(), 0, 100)).hasSize(2);
    }

    @Test
    void stableSourceIdsDeduplicateButDifferentContentNeverDoes() {
        Job duplicate = create(entry("a", "same") + entry("a", "same"));
        var result = service.advance(owner, duplicate.id(), 0);
        assertThat(result.importedMessages()).isEqualTo(2);
        assertThat(history(duplicate)).hasSize(1);
        assertThat(jobs.mappings(owner, duplicate.id(), 0, 100))
                .extracting(m -> m.archiveSeq())
                .containsExactly(1L, 1L);
        Job conflict = create(entry("a", "first") + entry("a", "different"));
        assertThatThrownBy(() -> service.advance(owner, conflict.id(), 0))
                .hasMessage("RUNTIME_MESSAGE_ID_CONTENT_CONFLICT");
        assertThat(history(conflict)).isEmpty();
        assertThat(jobs.mappings(owner, conflict.id(), 0, 100)).isEmpty();
    }

    @Test
    void checksumMismatchIsRejectedBeforeAnyArchiveCommit() {
        Job job = create(entry("a", "first"));
        var descriptor = files.describeVersion(tenant, job.sourceVersionId()).orElseThrow();
        FileCatalogService corrupt = mock(FileCatalogService.class);
        when(corrupt.describeVersion(any(), any()))
                .thenReturn(
                        java.util.Optional.of(
                                new FileCatalogService.VersionStream(
                                        descriptor.logicalPath(),
                                        descriptor.contentType(),
                                        descriptor.sizeBytes(),
                                        descriptor.sha256(),
                                        () ->
                                                new ByteArrayInputStream(
                                                        new byte[(int) descriptor.sizeBytes()]))));
        assertThatThrownBy(() -> service(jobs, corrupt).advance(owner, job.id(), 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(history(job)).isEmpty();
        assertThat(service.get(owner, job.id()).lineNo()).isZero();
    }

    @Test
    void invalidJsonAndMissingRolesDoNotSilentlyDropEvidence() {
        for (String source :
                List.of(
                        "bad\n",
                        "{\"type\":\"message\",\"content\":\"text\"}\n",
                        "{\"type\":\"unknown\"}\n",
                        "{\"type\":\"message\",\"role\":\"USER\",\"role\":\"TOOL\",\"content\":\"text\"}\n")) {
            Job job = create(source);
            assertThatThrownBy(() -> service.advance(owner, job.id(), 0))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(service.get(owner, job.id()).byteOffset()).isZero();
            assertThat(history(job)).isEmpty();
        }
    }

    @Test
    void emptyFileHasVerifiedCompleteProgressAndNoMessages() {
        Job job = create("");
        Job result = service.advance(owner, job.id(), 0);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.lineNo()).isZero();
        assertThat(history(job)).isEmpty();
    }

    @Test
    void foreignOwnerAndWrongAgentCannotReadOrImportSources() {
        Job job = create(entry("a", "first"));
        Owner otherUser =
                new Owner(owner.orgId(), UUID.randomUUID(), owner.agentId(), owner.sessionId());
        Owner otherOrg =
                new Owner(UUID.randomUUID(), owner.userId(), owner.agentId(), owner.sessionId());
        Owner otherAgent =
                new Owner(owner.orgId(), owner.userId(), UUID.randomUUID(), owner.sessionId());
        for (Owner foreign : List.of(otherUser, otherOrg, otherAgent)) {
            assertThatThrownBy(() -> service.get(foreign, job.id()))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.create(foreign, job.sourceVersionId()))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(jobs.find(foreign, job.id())).isEmpty();
        }
        assertThat(TenantContextHolder.getOrgId()).isEqualTo(owner.orgId().toString());
    }

    @Test
    void sourceVersionIsPinnedUntilSessionIsDeleted() {
        Job job = create(entry("a", "first"));
        assertThatThrownBy(
                        () -> {
                            var gc =
                                    MyBatisRepositoryTestSupport.mapper(
                                            dataSource,
                                            io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper
                                                    .class);
                            gc.deleteFileVersion(job.sourceVersionId());
                        })
                .isInstanceOf(RuntimeException.class);
        sessions.deleteById(owner.sessionId());
        assertThat(jobs.find(owner, job.id())).isEmpty();
    }

    @Test
    void disabledDeploymentDoesNotExposeImportOrMutateSources() {
        Job job = create(entry("a", "first"));
        var cfg = new SaasProperties();
        var disabled =
                new LegacyArchiveImportService(
                        jobs,
                        messages,
                        sessions,
                        runs,
                        versions,
                        files,
                        archive,
                        transfers,
                        cfg,
                        transactions,
                        json);
        assertThatThrownBy(() -> disabled.get(owner, job.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> disabled.advance(owner, job.id(), 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jobs.find(owner, job.id()).orElseThrow().lineNo()).isZero();
    }

    @Test
    void sessionResetClearsImportAndPreventsInFlightSourceResurrection() {
        Job job = create(entry("a", "first"));
        FileCatalogService resetting = mock(FileCatalogService.class);
        when(resetting.describeVersion(any(), any()))
                .thenAnswer(
                        call -> {
                            var descriptor =
                                    files.describeVersion(call.getArgument(0), call.getArgument(1));
                            chats.resetSession(owner.sessionId());
                            return descriptor;
                        });
        assertThatThrownBy(() -> service(jobs, resetting).advance(owner, job.id(), 0))
                .isInstanceOf(
                        io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException
                                .class);
        assertThat(jobs.find(owner, job.id())).isEmpty();
        assertThat(history(job)).isEmpty();
        Job fresh = service.create(owner, job.sourceVersionId());
        assertThat(fresh.id()).isNotEqualTo(job.id());
        assertThat(fresh.sessionGeneration()).isEqualTo(job.sessionGeneration() + 1);
        assertThat(service.advance(owner, fresh.id(), 0).status()).isEqualTo("COMPLETE");
    }

    @Test
    void multiBatchRequestVerifiesObjectOnlyOnceAndCommitsEachBoundedRange() {
        Job job =
                create(
                        entry("a", "first")
                                + entry("b", "second")
                                + entry("c", "third")
                                + entry("d", "fourth")
                                + entry(null, "fifth"));
        FileCatalogService measured = mock(FileCatalogService.class);
        var descriptor = files.describeVersion(tenant, job.sourceVersionId()).orElseThrow();
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        when(measured.describeVersion(any(), any()))
                .thenReturn(
                        java.util.Optional.of(
                                new FileCatalogService.VersionStream(
                                        descriptor.logicalPath(),
                                        descriptor.contentType(),
                                        descriptor.sizeBytes(),
                                        descriptor.sha256(),
                                        () -> {
                                            assertThat(
                                                            TransactionSynchronizationManager
                                                                    .isActualTransactionActive())
                                                    .isFalse();
                                            opens.incrementAndGet();
                                            return descriptor.source().open();
                                        })));
        var result = service(jobs, measured).advance(owner, job.id(), 0, 10);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(opens).hasValue(1);
        assertThat(jobs.proof(owner, job.id()).entries()).isEqualTo(5);
        assertThat(history(job)).hasSize(5);
    }

    private LegacyArchiveImportService service(
            LegacyArchiveImportRepository repository, FileCatalogService catalog) {
        return new LegacyArchiveImportService(
                repository,
                messages,
                sessions,
                runs,
                versions,
                catalog,
                archive,
                transfers,
                properties,
                transactions,
                json);
    }
}
