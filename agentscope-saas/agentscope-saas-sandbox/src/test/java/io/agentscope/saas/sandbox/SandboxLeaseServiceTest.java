/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxLeaseServiceTest {

    @Test
    void drivesProvisioningActiveHeartbeatAndReleaseTransitions() {
        SandboxLeaseRepository repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        when(repository.insert(any())).thenReturn(1);
        when(repository.activate(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
        when(repository.checkpoint(any(), any(), any(), any())).thenReturn(1);
        when(repository.release(any(), any(), any())).thenReturn(1);
        SandboxLeaseService service = new SandboxLeaseService(repository);
        UUID orgId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(1);

        SandboxLeaseContext lease =
                service.begin(
                        orgId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        attemptId,
                        new ActiveSandboxDeployment(
                                "OpenSandbox",
                                "runtime:latest",
                                java.util.Set.of(SandboxCapability.SNAPSHOT),
                                "{\"capabilities\":[\"SNAPSHOT\"]}"),
                        "worker-1",
                        expiresAt);

        assertThat(service.activate(lease, "sandbox-1", "{}", expiresAt)).isTrue();
        assertThat(service.heartbeat(lease, expiresAt.plusSeconds(10))).isTrue();
        assertThat(service.checkpoint(lease, "workspace-catalog://attempt/1", "v1")).isTrue();
        assertThat(service.release(lease)).isTrue();
        verify(repository).insert(any());
        verify(repository).activate(any(), any(), any(), any(), any(), any());
        verify(repository).heartbeat(any(), any(), any(), any());
        verify(repository).checkpoint(any(), any(), any(), any());
        verify(repository).release(any(), any(), any());
    }

    @Test
    void recordsSanitizedProvisioningFailureAsTerminalRelease() {
        SandboxLeaseRepository repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        when(repository.releaseAfterProvisioningFailure(any(), any(), any(), any())).thenReturn(1);
        SandboxLeaseService service = new SandboxLeaseService(repository);
        SandboxLeaseContext lease = new SandboxLeaseContext(UUID.randomUUID(), UUID.randomUUID());

        assertThat(
                        service.provisioningFailed(
                                lease, new IllegalStateException("provider\n unavailable")))
                .isTrue();

        verify(repository)
                .releaseAfterProvisioningFailure(
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("provider unavailable"));
    }

    @Test
    void checkpointsReleasedAttemptLeaseWhenTheDerivedContextBindingIsAbsent() {
        var repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        var lease = storedLease();
        when(repository.findByAttemptId(lease.attemptId(), lease.orgId()))
                .thenReturn(Optional.of(lease));
        when(repository.checkpoint(any(), any(), any(), any())).thenReturn(1);
        var service = new SandboxLeaseService(repository);
        assertThat(
                        service.checkpointForAttempt(
                                null,
                                lease.orgId(),
                                lease.userId(),
                                lease.runId(),
                                lease.taskId(),
                                lease.attemptId(),
                                "uri",
                                "version"))
                .isTrue();
        verify(repository).checkpoint(lease.id(), lease.orgId(), "uri", "version");
        verify(repository, never()).activate(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"org", "user", "run", "task", "attempt"})
    void explicitLeaseCannotPublishACheckpointForAnotherExecution(String mismatch) {
        var repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        var lease = storedLease();
        when(repository.findById(lease.id(), lease.orgId())).thenReturn(Optional.of(lease));
        var service = new SandboxLeaseService(repository);
        var context = new SandboxLeaseContext(lease.id(), lease.orgId());
        assertThatThrownBy(
                        () ->
                                service.checkpointForAttempt(
                                        context,
                                        mismatch.equals("org") ? UUID.randomUUID() : lease.orgId(),
                                        mismatch.equals("user")
                                                ? UUID.randomUUID()
                                                : lease.userId(),
                                        mismatch.equals("run") ? UUID.randomUUID() : lease.runId(),
                                        mismatch.equals("task")
                                                ? UUID.randomUUID()
                                                : lease.taskId(),
                                        mismatch.equals("attempt")
                                                ? UUID.randomUUID()
                                                : lease.attemptId(),
                                        "uri",
                                        "version"))
                .hasMessageContaining("SCOPE_MISMATCH");
        verify(repository, never()).checkpoint(any(), any(), any(), any());
    }

    @Test
    void missingExplicitBindingIsRejectedButExecutionWithoutAResourceLeaseNeedsNoUpdate() {
        var repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        var lease = storedLease();
        var service = new SandboxLeaseService(repository);
        assertThat(
                        service.checkpointForAttempt(
                                new SandboxLeaseContext(lease.id(), lease.orgId()),
                                lease.orgId(),
                                lease.userId(),
                                lease.runId(),
                                lease.taskId(),
                                lease.attemptId(),
                                "uri",
                                "version"))
                .isFalse();
        assertThat(
                        service.checkpointForAttempt(
                                null,
                                lease.orgId(),
                                lease.userId(),
                                lease.runId(),
                                lease.taskId(),
                                lease.attemptId(),
                                "uri",
                                "version"))
                .isTrue();
        verify(repository, never()).checkpoint(any(), any(), any(), any());
    }

    private static SandboxLeaseRepository.SandboxLease storedLease() {
        var now = OffsetDateTime.now();
        return new SandboxLeaseRepository.SandboxLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "opensandbox",
                "external",
                "{}",
                "test",
                "[]",
                null,
                null,
                "RELEASED",
                "resource-owner",
                now,
                now,
                now,
                now,
                null);
    }
}
