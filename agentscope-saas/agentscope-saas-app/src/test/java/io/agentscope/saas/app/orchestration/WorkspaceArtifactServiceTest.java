/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.app.workspace.WorkspaceCheckpointContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.NewRunArtifact;
import io.agentscope.saas.sandbox.SandboxLeaseContext;
import io.agentscope.saas.sandbox.SandboxLeaseService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceArtifactServiceTest {

    @Test
    void publishesAttemptArtifactsAndUpdatesTheProviderNeutralCheckpoint() {
        RunArtifactRepository repository = mock(RunArtifactRepository.class);
        SandboxLeaseService leaseService = mock(SandboxLeaseService.class);
        when(repository.insert(any())).thenReturn(1);
        when(leaseService.checkpoint(any(), any(), any())).thenReturn(true);
        WorkspaceArtifactService service =
                new WorkspaceArtifactService(repository, leaseService, new ObjectMapper());
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(true);
        FileRecord file =
                new FileRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "/generated/report.txt",
                        3,
                        "objects/report",
                        "minio",
                        12,
                        "abc123");
        checkpoint.recordFile(file);
        checkpoint.projectionSucceeded(1);
        checkpoint.statePersisted();
        checkpoint.sandboxStopped();
        UUID orgId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        SandboxLeaseContext lease = new SandboxLeaseContext(UUID.randomUUID(), orgId);

        WorkspaceArtifactService.Publication publication =
                service.publish(orgId, runId, taskId, attemptId, lease, checkpoint);

        assertThat(publication.uri())
                .isEqualTo("workspace-catalog://runs/" + runId + "/attempts/" + attemptId);
        assertThat(publication.version()).hasSize(64);
        assertThat(publication.artifactCount()).isEqualTo(1);
        ArgumentCaptor<NewRunArtifact> artifact = ArgumentCaptor.forClass(NewRunArtifact.class);
        verify(repository).insert(artifact.capture());
        assertThat(artifact.getValue().attemptId()).isEqualTo(attemptId);
        assertThat(artifact.getValue().fileVersionId()).isEqualTo(file.versionId());
        assertThat(artifact.getValue().logicalPath()).isEqualTo(file.logicalPath());
        assertThat(artifact.getValue().evidenceJson())
                .contains("\"logicalPath\":\"/generated/report.txt\"")
                .contains("\"sandboxStopped\":true");
        verify(leaseService).checkpoint(lease, publication.uri(), publication.version());
    }

    @Test
    void rejectsIncompleteCatalogPublicationBeforeWritingArtifacts() {
        WorkspaceArtifactService service =
                new WorkspaceArtifactService(
                        mock(RunArtifactRepository.class),
                        mock(SandboxLeaseService.class),
                        new ObjectMapper());
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.projectionSucceeded(1);

        assertThatThrownBy(
                        () ->
                                service.publish(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        null,
                                        checkpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projected 1 files but cataloged 0");
    }

    @Test
    void marksOnlyChangedVersionsAsProducedInInteractiveRun() {
        RunArtifactRepository repository = mock(RunArtifactRepository.class);
        when(repository.insert(any())).thenReturn(1);
        WorkspaceArtifactService service =
                new WorkspaceArtifactService(
                        repository, mock(SandboxLeaseService.class), new ObjectMapper());
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(true);
        UUID oldVersion = UUID.randomUUID();
        FileRecord oldFile =
                new FileRecord(
                        UUID.randomUUID(),
                        oldVersion,
                        "outputs/previous.txt",
                        1,
                        "old",
                        "minio",
                        4,
                        "oldhash");
        FileRecord newFile =
                new FileRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "outputs/new.txt",
                        1,
                        "new",
                        "minio",
                        4,
                        "newhash");
        checkpoint.recordFile(oldFile);
        checkpoint.recordFile(newFile);
        checkpoint.projectionSucceeded(2);

        service.publish(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                checkpoint,
                Map.of(oldFile.logicalPath(), oldVersion));

        ArgumentCaptor<NewRunArtifact> captured = ArgumentCaptor.forClass(NewRunArtifact.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .insert(captured.capture());
        assertThat(captured.getAllValues())
                .filteredOn(a -> a.logicalPath().equals(oldFile.logicalPath()))
                .singleElement()
                .satisfies(a -> assertThat(a.evidenceJson()).contains("\"producedInRun\":false"));
        assertThat(captured.getAllValues())
                .filteredOn(a -> a.logicalPath().equals(newFile.logicalPath()))
                .singleElement()
                .satisfies(a -> assertThat(a.evidenceJson()).contains("\"producedInRun\":true"));
    }

    @Test
    void rejectsCheckpointWhenDurableProjectionStoreIsUnavailable() {
        WorkspaceArtifactService service =
                new WorkspaceArtifactService(
                        mock(RunArtifactRepository.class),
                        mock(SandboxLeaseService.class),
                        new ObjectMapper());
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(false);
        checkpoint.projectionSucceeded(0);

        assertThatThrownBy(
                        () ->
                                service.publish(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        null,
                                        checkpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable BaseStore is unavailable");
    }
}
