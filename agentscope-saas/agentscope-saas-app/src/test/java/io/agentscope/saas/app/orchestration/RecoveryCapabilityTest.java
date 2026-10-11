/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository.Presence;
import org.junit.jupiter.api.Test;

class RecoveryCapabilityTest {
    @Test
    void externalConsumersNeedAQualifiedSharedScopeNotJustAFlag() {
        var config = durable();
        config.getOrchestration().setSchedulerEnabled(false);
        assertThatThrownBy(() -> new RecoveryCapability(config, null))
                .isInstanceOf(IllegalArgumentException.class);
        config.getOrchestration()
                .setConsumerMode(SaasProperties.Orchestration.ConsumerMode.EXTERNAL);
        assertThatThrownBy(() -> new RecoveryCapability(config, null))
                .isInstanceOf(IllegalArgumentException.class);
        config.getOrchestration().setDeploymentScope("enterprise-a");
        var capability = new RecoveryCapability(config, mock(WorkerPresenceRepository.class));
        assertThat(capability.durableConfigured()).isTrue();
        assertThat(capability.localConsumerEnabled()).isFalse();
    }

    @Test
    void liveAbsentBusyAndExpiredConsumersAreDistinctAndOldQueuesRemainRecoverable() {
        var repository = mock(WorkerPresenceRepository.class);
        var capability = new RecoveryCapability(durable(), repository);
        String scope = capability.deploymentScope(), hash = capability.capabilityHash();
        when(repository.presence(scope, hash)).thenReturn(new Presence(0, 0, 0));
        assertThat(capability.snapshot().canEnqueue()).isFalse();
        assertThat(capability.snapshot().state()).isEqualTo("CONSUMER_NOT_REGISTERED");
        when(repository.presence(scope, hash)).thenReturn(new Presence(1, 0, 0));
        assertThat(capability.snapshot().canEnqueue()).isTrue();
        assertThat(capability.snapshot().state()).isEqualTo("WAITING_FOR_WORKER");
        when(repository.presence(scope, hash)).thenReturn(new Presence(1, 1, 0));
        assertThat(capability.snapshot().state()).isEqualTo("WAITING_FOR_CAPACITY");
        when(repository.presence(scope, hash)).thenReturn(new Presence(1, 1, 2));
        assertThat(capability.snapshot().state()).isEqualTo("AVAILABLE");
    }

    @Test
    void protocolCompatibilityIncludesProviderButNotCredentialsOrMutableAdmissionLimits() {
        var first = durable();
        var second = durable();
        var hash = new RecoveryCapability(first, null).capabilityHash();
        second.getModel().setApiKey("another-secret");
        assertThat(new RecoveryCapability(second, null).capabilityHash()).isEqualTo(hash);
        second.getFileStore().setMaxFileBytes(64L * 1024 * 1024);
        assertThat(new RecoveryCapability(second, null).capabilityHash()).isEqualTo(hash);
        second.getFileStore().setMaxFileBytes(first.getFileStore().getMaxFileBytes());
        second.getSandbox().setType("opensandbox");
        assertThat(new RecoveryCapability(second, null).capabilityHash()).isNotEqualTo(hash);
    }

    @Test
    void requestOnlyDoesNotReadOrRelyOnConsumerPresence() {
        var repository = mock(WorkerPresenceRepository.class);
        var capability = new RecoveryCapability(new SaasProperties(), repository);
        assertThat(capability.snapshot().canEnqueue()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void changingRuntimeClusterOrObjectBucketRequiresACompatibleConsumerButKeyRotationDoesNot() {
        var first = durable();
        first.getSandbox().setType("opensandbox");
        first.getSandbox().setEnabled(true);
        first.getFileStore().setBackend("minio");
        var original = new RecoveryCapability(first, null).capabilityHash();
        first.getSandbox().setOpenSandboxApiKey("rotated-not-part-of-contract");
        assertThat(new RecoveryCapability(first, null).capabilityHash()).isEqualTo(original);
        first.getSandbox().setOpenSandboxApiBaseUrl("http://other-runtime.internal");
        assertThat(new RecoveryCapability(first, null).capabilityHash()).isNotEqualTo(original);
        var changedCluster = new RecoveryCapability(first, null).capabilityHash();
        first.getFileStore().getMinio().setBucket("other-workspace");
        assertThat(new RecoveryCapability(first, null).capabilityHash())
                .isNotEqualTo(changedCluster);
    }

    @Test
    void durableSandboxRecoveryRejectsMissingCatalogAndInvalidHeartbeatPolicy() {
        var config = durable();
        config.getSandbox().setEnabled(true);
        config.getFileStore().setEnabled(false);
        assertThatThrownBy(() -> new RecoveryCapability(config, null))
                .isInstanceOf(IllegalArgumentException.class);
        config.getFileStore().setEnabled(true);
        config.getOrchestration().setConsumerHeartbeatTtlSeconds(20);
        assertThatThrownBy(() -> new RecoveryCapability(config, null))
                .isInstanceOf(IllegalArgumentException.class);
        config.getOrchestration().setConsumerHeartbeatTtlSeconds(90);
        config.getOrchestration().setSchedulerHeartbeatSeconds(Long.MAX_VALUE);
        assertThatThrownBy(() -> new RecoveryCapability(config, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static SaasProperties durable() {
        var config = new SaasProperties();
        config.getOrchestration().setSchedulerEnabled(true);
        config.getModel()
                .getStreamRecovery()
                .setMode(SaasProperties.ModelStreamRecovery.Mode.DURABLE);
        return config;
    }
}
