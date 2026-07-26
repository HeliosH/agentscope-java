/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.domain.sandbox.SandboxLeaseRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxLeaseServiceTest {

    @Test
    void drivesProvisioningActiveHeartbeatAndReleaseTransitions() {
        SandboxLeaseRepository repository = org.mockito.Mockito.mock(SandboxLeaseRepository.class);
        when(repository.insert(any())).thenReturn(1);
        when(repository.activate(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
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
        assertThat(service.release(lease)).isTrue();
        verify(repository).insert(any());
        verify(repository).activate(any(), any(), any(), any(), any(), any());
        verify(repository).heartbeat(any(), any(), any(), any());
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
}
