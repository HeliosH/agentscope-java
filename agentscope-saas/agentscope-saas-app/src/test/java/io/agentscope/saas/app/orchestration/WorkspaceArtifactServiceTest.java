/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.app.workspace.WorkspaceManifestService;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.ArtifactReceipt;
import io.agentscope.saas.sandbox.SandboxLeaseContext;
import io.agentscope.saas.sandbox.SandboxLeaseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceArtifactServiceTest {
    final RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
    final FilePublicationRepository publications = mock(FilePublicationRepository.class);
    final SandboxLeaseService leases = mock(SandboxLeaseService.class);
    final WorkspaceArtifactService service =
            new WorkspaceArtifactService(
                    artifacts, leases, publications, mock(WorkspaceManifestService.class));
    final UUID org = UUID.randomUUID(),
            run = UUID.randomUUID(),
            task = UUID.randomUUID(),
            attempt = UUID.randomUUID();
    final UUID manifestId = UUID.randomUUID();
    final FileRecord file =
            new FileRecord(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "outputs/report.txt",
                    3,
                    "objects/report",
                    "minio",
                    12,
                    "abc123");

    @Test
    void verifiesDurableReceiptsBeforeUpdatingProviderNeutralCheckpoint() {
        when(artifacts.lockCurrentPublicationScope(org, run, task, attempt)).thenReturn(true);
        when(publications.artifactReceipts(org, run, task, attempt))
                .thenReturn(List.of(receipt("")));
        when(leases.checkpointForAttempt(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        var lease = new SandboxLeaseContext(UUID.randomUUID(), org);
        var result = service.publish(org, run, task, attempt, lease, checkpoint());
        assertThat(result.uri())
                .isEqualTo(WorkspaceCheckpointReference.pinned(run, attempt, manifestId));
        assertThat(result.version()).hasSize(64);
        assertThat(result.artifactCount()).isEqualTo(1);
        verify(leases)
                .checkpointForAttempt(
                        eq(lease),
                        eq(org),
                        any(),
                        eq(run),
                        eq(task),
                        eq(attempt),
                        eq(result.uri()),
                        eq(result.version()));
        verify(artifacts, never()).insert(any());
    }

    @Test
    void rejectsIncompleteCatalogBeforeQueryingReceipts() {
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.projectionSucceeded(1);
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint))
                .hasMessageContaining("projected 1 files but cataloged 0");
        verifyNoInteractions(publications, artifacts, leases);
    }

    @Test
    void missingReceiptCannotBeRepairedFromAnInMemoryFileRecord() {
        when(artifacts.lockCurrentPublicationScope(org, run, task, attempt)).thenReturn(true);
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint()))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
        verify(artifacts, never()).insert(any());
        verifyNoInteractions(leases);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "path", "key", "backend", "hash", "size", "version"})
    void rejectsMismatchedImmutableReceipt(String mismatch) {
        when(artifacts.lockCurrentPublicationScope(org, run, task, attempt)).thenReturn(true);
        when(publications.artifactReceipts(org, run, task, attempt))
                .thenReturn(List.of(receipt(mismatch)));
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint()))
                .hasMessageContaining("FILE_ARTIFACT_RECEIPT_MISSING_OR_MISMATCHED");
        verifyNoInteractions(leases);
    }

    @Test
    void rejectsRevokedExecutionBeforeUsingExistingReceipts() {
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint()))
                .isInstanceOf(SessionExecutionRevokedException.class);
        verifyNoInteractions(publications, leases);
    }

    @Test
    void rejectsDuplicatePathsInCheckpoint() {
        when(artifacts.lockCurrentPublicationScope(org, run, task, attempt)).thenReturn(true);
        when(publications.artifactReceipts(org, run, task, attempt))
                .thenReturn(List.of(receipt("")));
        var checkpoint = checkpoint();
        checkpoint.recordFile(file);
        checkpoint.projectionSucceeded(2);
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint))
                .hasMessageContaining("WORKSPACE_CHECKPOINT_DUPLICATE_FILE");
    }

    @Test
    void rejectsCheckpointWithoutDurableProjectionStore() {
        var checkpoint = new WorkspaceCheckpointContext(false);
        checkpoint.projectionSucceeded(0);
        assertThatThrownBy(() -> service.publish(org, run, task, attempt, null, checkpoint))
                .hasMessageContaining("durable BaseStore is unavailable");
        verifyNoInteractions(publications, artifacts, leases);
    }

    private WorkspaceCheckpointContext checkpoint() {
        var checkpoint = new WorkspaceCheckpointContext(true);
        var manifest =
                mock(
                        io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Manifest
                                .class);
        when(manifest.id()).thenReturn(manifestId);
        var scope =
                new io.agentscope.saas.domain.workspace.WorkspaceManifestRepository.Scope(
                        org,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        0,
                        run,
                        new io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution(
                                task, UUID.randomUUID(), attempt, "worker"));
        when(manifest.scope()).thenReturn(scope);
        checkpoint.planned(manifest);
        checkpoint.recordFile(file);
        checkpoint.projectionSucceeded(1);
        return checkpoint;
    }

    private ArtifactReceipt receipt(String mismatch) {
        return new ArtifactReceipt(
                UUID.randomUUID(),
                UUID.randomUUID(),
                mismatch.equals("file") ? UUID.randomUUID() : file.fileId(),
                mismatch.equals("version") ? UUID.randomUUID() : file.versionId(),
                mismatch.equals("path") ? "outputs/other.txt" : file.logicalPath(),
                mismatch.equals("key") ? "objects/other" : file.objectKey(),
                mismatch.equals("backend") ? "pg" : file.storageBackend(),
                mismatch.equals("hash") ? "different" : file.sha256(),
                mismatch.equals("size") ? 13 : file.sizeBytes());
    }
}
