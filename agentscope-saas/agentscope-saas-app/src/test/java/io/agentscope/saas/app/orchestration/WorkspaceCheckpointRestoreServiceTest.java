/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.workspace.FileCatalogService;
import io.agentscope.saas.app.workspace.FileCatalogService.StoredFile;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository.SandboxLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceCheckpointRestoreServiceTest {

    private final SandboxLeaseRepository leases = mock(SandboxLeaseRepository.class);
    private final RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
    private final FileCatalogService files = mock(FileCatalogService.class);
    private final WorkspaceCheckpointRestoreService service =
            new WorkspaceCheckpointRestoreService(leases, artifacts, files, new ObjectMapper());

    @Test
    void restoresLatestCheckpointFromImmutableArtifactVersions() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID currentAttemptId = UUID.randomUUID();
        UUID previousAttemptId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        byte[] content = "durable result".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(content);
        String version =
                WorkspaceManifestVersion.compute(
                        List.of(
                                new WorkspaceManifestVersion.Entry(
                                        "generated/report.txt", versionId, sha)));
        SandboxLease checkpoint = checkpoint(orgId, previousAttemptId, version);
        RunArtifact artifact =
                artifact(orgId, previousAttemptId, fileId, versionId, "generated/report.txt");
        TenantContext tenant =
                new TenantContext(
                        orgId.toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        2,
                        10_000);
        when(leases.findLatestCheckpointBeforeAttempt(currentAttemptId, orgId))
                .thenReturn(Optional.of(checkpoint));
        when(artifacts.findByAttemptId(previousAttemptId, orgId)).thenReturn(List.of(artifact));
        when(files.readVersion(tenant, versionId))
                .thenReturn(
                        Optional.of(
                                new StoredFile(
                                        "renamed-later.txt",
                                        "text/plain",
                                        content,
                                        (long) content.length,
                                        sha)));

        var plan =
                service.prepare(
                                tenant,
                                orgId,
                                currentAttemptId,
                                WorkspaceIsolationMode.ATTEMPT_ISOLATED)
                        .orElseThrow();

        assertThat(plan.checkpointUri()).isEqualTo(checkpoint.workspaceSnapshotUri());
        assertThat(plan.workspaceVersion()).isEqualTo(version);
        assertThat(plan.files())
                .singleElement()
                .satisfies(
                        file -> {
                            assertThat(file.path()).isEqualTo("generated/report.txt");
                            assertThat(file.content()).isEqualTo(content);
                        });
    }

    @Test
    void failsClosedWhenManifestDoesNotMatchCheckpoint() {
        UUID orgId = UUID.randomUUID();
        UUID currentAttemptId = UUID.randomUUID();
        UUID previousAttemptId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TenantContext tenant =
                new TenantContext(
                        orgId.toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        1,
                        1_000);
        when(leases.findLatestCheckpointBeforeAttempt(currentAttemptId, orgId))
                .thenReturn(Optional.of(checkpoint(orgId, previousAttemptId, "wrong-version")));
        when(artifacts.findByAttemptId(previousAttemptId, orgId))
                .thenReturn(
                        List.of(
                                artifact(
                                        orgId,
                                        previousAttemptId,
                                        UUID.randomUUID(),
                                        versionId,
                                        "result.txt")));
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        when(files.readVersion(tenant, versionId))
                .thenReturn(
                        Optional.of(
                                new StoredFile(
                                        "result.txt",
                                        "text/plain",
                                        content,
                                        (long) content.length,
                                        sha256(content))));

        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        tenant,
                                        orgId,
                                        currentAttemptId,
                                        WorkspaceIsolationMode.DEDICATED_SANDBOX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest mismatch");
    }

    @Test
    void failsClosedWhenStoredBytesDoNotMatchCatalogDigest() {
        UUID orgId = UUID.randomUUID();
        UUID currentAttemptId = UUID.randomUUID();
        UUID previousAttemptId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TenantContext tenant =
                new TenantContext(
                        orgId.toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        1,
                        1_000);
        when(leases.findLatestCheckpointBeforeAttempt(currentAttemptId, orgId))
                .thenReturn(Optional.of(checkpoint(orgId, previousAttemptId, "unused")));
        when(artifacts.findByAttemptId(previousAttemptId, orgId))
                .thenReturn(
                        List.of(
                                artifact(
                                        orgId,
                                        previousAttemptId,
                                        UUID.randomUUID(),
                                        versionId,
                                        "result.txt")));
        byte[] corrupted = "corrupted".getBytes(StandardCharsets.UTF_8);
        when(files.readVersion(tenant, versionId))
                .thenReturn(
                        Optional.of(
                                new StoredFile(
                                        "result.txt",
                                        "text/plain",
                                        corrupted,
                                        (long) corrupted.length,
                                        sha256("expected".getBytes(StandardCharsets.UTF_8)))));

        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        tenant,
                                        orgId,
                                        currentAttemptId,
                                        WorkspaceIsolationMode.ATTEMPT_ISOLATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content digest mismatch");
    }

    @Test
    void sharedWorkspaceModesDoNotReplayAttemptArtifacts() {
        service.prepare(
                mock(TenantContext.class),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WorkspaceIsolationMode.NONE);

        verifyNoInteractions(leases, artifacts, files);
    }

    private static SandboxLease checkpoint(UUID orgId, UUID attemptId, String version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SandboxLease(
                UUID.randomUUID(),
                orgId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                attemptId,
                "opensandbox",
                null,
                null,
                "template",
                "{}",
                "workspace-catalog://attempts/" + attemptId,
                version,
                "RELEASED",
                "worker",
                now,
                now,
                now,
                now,
                null);
    }

    private static RunArtifact artifact(
            UUID orgId, UUID attemptId, UUID fileId, UUID versionId, String logicalPath) {
        return new RunArtifact(
                UUID.randomUUID(),
                orgId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                attemptId,
                fileId,
                versionId,
                logicalPath,
                WorkspaceArtifactService.ARTIFACT_TYPE_WORKSPACE_FILE,
                "{}",
                OffsetDateTime.now());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
