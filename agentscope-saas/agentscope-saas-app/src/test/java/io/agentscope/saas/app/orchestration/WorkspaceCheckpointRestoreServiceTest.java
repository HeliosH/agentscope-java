/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionPlan;
import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.app.workspace.FileCatalogService.StoredFile;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.SandboxLease;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Entry;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WorkspaceCheckpointRestoreServiceTest {
    final SandboxLeaseRepository leases = mock(SandboxLeaseRepository.class);
    final RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
    final FileCatalogService files = mock(FileCatalogService.class);
    final WorkspaceManifestRepository manifests = mock(WorkspaceManifestRepository.class);
    final SaasProperties properties = new SaasProperties();
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final WorkspaceCheckpointRestoreService service =
            new WorkspaceCheckpointRestoreService(
                    leases, artifacts, files, new ObjectMapper(), manifests, properties, manager);
    final UUID org = UUID.randomUUID(),
            user = UUID.randomUUID(),
            agent = UUID.randomUUID(),
            session = UUID.randomUUID(),
            run = UUID.randomUUID(),
            task = UUID.randomUUID(),
            previous = UUID.randomUUID(),
            current = UUID.randomUUID(),
            manifestId = UUID.randomUUID(),
            fileId = UUID.randomUUID(),
            versionId = UUID.randomUUID();
    final TenantContext tenant =
            new TenantContext(org.toString(), user.toString(), "member", "standard", 2, 10000);
    final Scope currentScope =
            new Scope(
                    org,
                    user,
                    agent,
                    session,
                    0,
                    run,
                    new Execution(task, UUID.randomUUID(), current, "new-owner"));
    final Scope sourceScope =
            new Scope(
                    org,
                    user,
                    agent,
                    session,
                    0,
                    run,
                    new Execution(task, UUID.randomUUID(), previous, "old-owner"));
    final byte[] content = "durable result".getBytes(StandardCharsets.UTF_8);
    final String sha = sha256(content);
    final String path = "generated/report.txt";
    final String version =
            WorkspaceManifestVersion.compute(
                    List.of(new WorkspaceManifestVersion.Entry(path, versionId, sha)));
    final Entry entry = new Entry(path, path, content.length, sha, false, UUID.randomUUID());

    @BeforeEach
    void initialize() {
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(manifests.restoreScope(org, user, current)).thenReturn(Optional.of(currentScope));
        when(manifests.lockScope(any(), any())).thenReturn(true);
        when(files.readVersion(tenant, versionId))
                .thenReturn(
                        Optional.of(
                                new StoredFile(
                                        "renamed-later.txt",
                                        "text/plain",
                                        content,
                                        (long) content.length,
                                        sha)));
    }

    @Test
    void restoresOnlyThePinnedVerifiedManifestWithoutEnumeratingHistoricalArtifacts() {
        pin("READY", true, List.of(entry), version);
        var plan = restore().orElseThrow();
        assertThat(plan.checkpointUri())
                .isEqualTo(WorkspaceCheckpointReference.pinned(run, previous, manifestId));
        assertThat(plan.workspaceVersion()).isEqualTo(version);
        assertThat(plan.files())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.path()).isEqualTo(path);
                            assertThat(f.content()).isEqualTo(content);
                        });
        verifyNoInteractions(artifacts);
    }

    @Test
    void legacyCheckpointWithoutAnyPublicationManifestRemainsReadable() {
        var cp = checkpoint("workspace-catalog://attempts/" + previous, version);
        stubCheckpoint(cp);
        when(artifacts.findByAttemptId(previous, org))
                .thenReturn(
                        List.of(
                                new RunArtifact(
                                        UUID.randomUUID(),
                                        org,
                                        run,
                                        task,
                                        previous,
                                        fileId,
                                        versionId,
                                        path,
                                        "WORKSPACE_FILE",
                                        "{}",
                                        OffsetDateTime.now())));
        assertThat(restore().orElseThrow().files())
                .singleElement()
                .satisfies(f -> assertThat(f.path()).isEqualTo(path));
    }

    @Test
    void unpinnedCheckpointCannotFallBackToHistoryIfAnyManifestExists() {
        stubCheckpoint(
                checkpoint("workspace-catalog://runs/" + run + "/attempts/" + previous, version));
        when(manifests.hasAttemptManifests(org, user, run, task, previous)).thenReturn(true);
        assertThatThrownBy(this::restore).hasMessageContaining("MANIFEST_REFERENCE_REQUIRED");
        verifyNoInteractions(artifacts);
        verify(files, never()).readVersion(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISCOVERED", "SCANNED"})
    void unfinishedManifestNeverRestoresEvenWithPublishedFiles(String status) {
        pin(status, true, List.of(entry), version);
        assertThatThrownBy(this::restore).hasMessageContaining("MANIFEST_NOT_READY");
        verify(files, never()).readVersion(any(), any());
    }

    @Test
    void readyFlagWithoutCompleteScanIsInsufficient() {
        pin("READY", false, List.of(entry), version);
        assertThatThrownBy(this::restore).hasMessageContaining("MANIFEST_NOT_READY");
    }

    @Test
    void missingReceiptCannotBeRecoveredFromAttemptArtifacts() {
        pin("READY", true, List.of(entry), version);
        when(manifests.receipts(sourceScope, manifestId)).thenReturn(List.of());
        assertThatThrownBy(this::restore).hasMessageContaining("RECEIPT_MISSING_OR_MISMATCHED");
        verifyNoInteractions(artifacts);
    }

    @Test
    void checkpointDigestMismatchFailsBeforeObjectReads() {
        pin("READY", true, List.of(entry), "wrong-version");
        assertThatThrownBy(this::restore).hasMessageContaining("manifest mismatch");
        verify(files, never()).readVersion(any(), any());
    }

    @Test
    void storedBytesMustMatchBothCatalogAndFrozenManifest() {
        pin("READY", true, List.of(entry), version);
        byte[] changed = "different bytes".getBytes(StandardCharsets.UTF_8);
        when(files.readVersion(tenant, versionId))
                .thenReturn(
                        Optional.of(
                                new StoredFile(
                                        path,
                                        "text/plain",
                                        changed,
                                        (long) changed.length,
                                        sha256(changed))));
        assertThatThrownBy(this::restore).hasMessageContaining("content digest mismatch");
    }

    @Test
    void missingImmutableVersionFailsClosed() {
        pin("READY", true, List.of(entry), version);
        when(files.readVersion(tenant, versionId)).thenReturn(Optional.empty());
        assertThatThrownBy(this::restore).hasMessageContaining("version is unavailable");
    }

    @Test
    void cancellationOrLeaseLossDuringObjectReadRevokesThePlan() {
        pin("READY", true, List.of(entry), version);
        var active = new AtomicBoolean(true);
        when(manifests.lockScope(any(), any())).thenAnswer(i -> active.get());
        when(files.readVersion(tenant, versionId))
                .thenAnswer(
                        i -> {
                            active.set(false);
                            return Optional.of(
                                    new StoredFile(
                                            path,
                                            "text/plain",
                                            content,
                                            (long) content.length,
                                            sha));
                        });
        assertThatThrownBy(this::restore).isInstanceOf(SessionExecutionRevokedException.class);
    }

    @Test
    void snapshotPointerChangedDuringObjectReadCannotReturnStaleFiles() {
        pin("READY", true, List.of(entry), version);
        when(files.readVersion(tenant, versionId))
                .thenAnswer(
                        i -> {
                            var cp =
                                    checkpoint(
                                            WorkspaceCheckpointReference.pinned(
                                                    run, previous, UUID.randomUUID()),
                                            version);
                            when(leases.findById(cp.id(), org)).thenReturn(Optional.of(cp));
                            return Optional.of(
                                    new StoredFile(
                                            path,
                                            "text/plain",
                                            content,
                                            (long) content.length,
                                            sha));
                        });
        assertThatThrownBy(this::restore).hasMessageContaining("CHANGED_DURING_RESTORE");
    }

    @Test
    void oldSessionGenerationCannotBeRestored() {
        pin("READY", true, List.of(entry), version);
        var forged = new Scope(org, user, agent, session, 1, run, sourceScope.execution());
        when(manifests.findCheckpoint(org, user, run, task, previous, manifestId))
                .thenReturn(Optional.of(manifest(forged, "READY", true, List.of(entry))));
        assertThatThrownBy(this::restore).isInstanceOf(SessionExecutionRevokedException.class);
    }

    @Test
    void transferLimitsAreCheckedBeforeObjectRead() {
        pin("READY", true, List.of(entry), version);
        properties.getSandbox().setTransferMaxBytes(content.length - 1);
        properties.getFileStore().setMaxFileBytes(content.length - 1);
        assertThatThrownBy(this::restore).hasMessageContaining("RESTORE_LIMIT");
        verify(files, never()).readVersion(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "a/../b", "a//b", "a\\b"})
    void unsafeFrozenPathsAreRejected(String unsafe) {
        var inventory =
                List.of(new Entry(unsafe, unsafe, content.length, sha, false, UUID.randomUUID()));
        pin("READY", true, inventory, version);
        assertThatThrownBy(this::restore).hasMessageContaining("UNSAFE_PATH");
    }

    @Test
    void sharedWorkspaceModesDoNotReplayAttemptArtifacts() {
        service.prepare(tenant, org, current, "new-owner", WorkspaceIsolationMode.NONE);
        verifyNoInteractions(leases, artifacts);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://runs/a",
                "workspace-catalog://runs/wrong",
                "workspace-catalog://runs/x?manifest=1"
            })
    void invalidReferencesCannotBecomeProviderRequests(String uri) {
        stubCheckpoint(checkpoint(uri, version));
        assertThatThrownBy(this::restore).hasMessageContaining("INVALID_REFERENCE");
        verifyNoInteractions(artifacts);
    }

    private Optional<io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan> restore() {
        return service.prepare(
                tenant, org, current, "new-owner", WorkspaceIsolationMode.ATTEMPT_ISOLATED);
    }

    @Test
    void oldWorkerCannotAdoptTheNewOwnersLeaseWhenPreparingRestore() {
        pin("READY", true, List.of(entry), version);
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        tenant,
                                        org,
                                        current,
                                        "old-worker",
                                        WorkspaceIsolationMode.ATTEMPT_ISOLATED))
                .isInstanceOf(SessionExecutionRevokedException.class);
        verifyNoInteractions(leases);
        verify(files, never()).readVersion(any(), any());
    }

    private void pin(String status, boolean complete, List<Entry> inventory, String digest) {
        stubCheckpoint(
                checkpoint(WorkspaceCheckpointReference.pinned(run, previous, manifestId), digest));
        when(manifests.findCheckpoint(org, user, run, task, previous, manifestId))
                .thenReturn(Optional.of(manifest(sourceScope, status, complete, inventory)));
        when(manifests.entries(org, manifestId)).thenReturn(inventory);
        when(manifests.receipts(sourceScope, manifestId))
                .thenReturn(
                        List.of(
                                new ArtifactReceipt(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        fileId,
                                        versionId,
                                        path,
                                        "object",
                                        "minio",
                                        sha,
                                        content.length)));
    }

    private Manifest manifest(Scope scope, String status, boolean complete, List<Entry> inventory) {
        long total = inventory.stream().mapToLong(Entry::sizeBytes).sum();
        var plan =
                new WorkspaceProjectionPlan(
                        inventory.stream()
                                .map(
                                        e ->
                                                new WorkspaceProjectionPlan.Entry(
                                                        e.sourcePath(),
                                                        e.logicalPath(),
                                                        e.sizeBytes(),
                                                        e.sha256(),
                                                        e.unchanged()))
                                .toList(),
                        inventory.size(),
                        total);
        return new Manifest(
                manifestId,
                scope,
                1,
                plan.version(),
                inventory.size(),
                total,
                inventory.size(),
                status,
                complete,
                OffsetDateTime.now());
    }

    final UUID checkpointId = UUID.randomUUID();

    private SandboxLease checkpoint(String uri, String digest) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SandboxLease(
                checkpointId,
                org,
                user,
                run,
                task,
                previous,
                "opensandbox",
                null,
                null,
                "template",
                "{}",
                uri,
                digest,
                "RELEASED",
                "sandbox-owner",
                now,
                now,
                now,
                now,
                null);
    }

    private void stubCheckpoint(SandboxLease checkpoint) {
        when(leases.findLatestCheckpointBeforeAttempt(current, org))
                .thenReturn(Optional.of(checkpoint));
        when(leases.findById(checkpoint.id(), org)).thenReturn(Optional.of(checkpoint));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
