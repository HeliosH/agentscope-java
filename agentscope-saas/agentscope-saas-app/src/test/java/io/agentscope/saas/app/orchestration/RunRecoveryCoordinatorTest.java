/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelStreamInterruptedException;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.saas.app.config.SaasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunRecoveryCoordinatorTest {
    @Test
    void persistedFailureCountCannotResetWhenMovingFromHttpToWorker() {
        var config = RecoveryCapabilityTest.durable();
        var coordinator = new RunRecoveryCoordinator(config);
        var ledger =
                org.mockito.Mockito.mock(
                        io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.class);
        coordinator.configureInvocationLedger(ledger);
        var scope =
                new io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        "worker-a");
        org.mockito.Mockito.when(ledger.failedReasoningCalls(scope)).thenReturn(2L);
        assertThat(coordinator.decide(new ModelException("offline"), 1, scope).attempt())
                .isEqualTo(3);
        org.mockito.Mockito.when(ledger.failedReasoningCalls(scope)).thenReturn(3L);
        var exhausted = coordinator.decide(new ModelException("offline"), 1, scope);
        assertThat(exhausted.recoverable()).isFalse();
        assertThat(exhausted.exhausted()).isTrue();
        org.mockito.Mockito.clearInvocations(ledger);
        coordinator.decide(new IllegalArgumentException("bad business input"), 1, scope);
        org.mockito.Mockito.verifyNoInteractions(ledger);
        org.mockito.Mockito.when(ledger.failedReasoningCalls(scope)).thenReturn(1L);
        assertThat(coordinator.decide(new ModelException("offline"), 12, scope).attempt())
                .isEqualTo(2);
        assertThat(coordinator.decide(new ModelException("offline"), 12, scope).recoverable())
                .isTrue();
        org.mockito.Mockito.clearInvocations(ledger);
        config.getModel().getStreamRecovery().setEnabled(false);
        assertThat(coordinator.decide(new ModelException("offline"), 1, scope).recoverable())
                .isFalse();
        org.mockito.Mockito.verifyNoInteractions(ledger);
    }

    @Test
    void requestOnlyNeverPromisesBackgroundRecoveryEvenWithWorkerEnabled() {
        var config = new SaasProperties();
        config.getOrchestration().setEnabled(true);
        config.getOrchestration().setSchedulerEnabled(true);
        assertThat(new RunRecoveryCoordinator(config).canScheduleDurableRecovery()).isFalse();
    }

    @Test
    void durableRecoveryRequiresAnEnabledWorker() {
        var config = new SaasProperties();
        config.getModel()
                .getStreamRecovery()
                .setMode(SaasProperties.ModelStreamRecovery.Mode.DURABLE);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new RunRecoveryCoordinator(config));
        config.getOrchestration().setEnabled(true);
        config.getOrchestration().setSchedulerEnabled(true);
        assertThat(new RunRecoveryCoordinator(config).canScheduleDurableRecovery()).isTrue();
        config.getModel().getStreamRecovery().setEnabled(false);
        assertThat(new RunRecoveryCoordinator(config).canScheduleDurableRecovery()).isFalse();
    }

    private SaasProperties properties;
    private RunRecoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new SaasProperties();
        properties.getModel().getStreamRecovery().setMaxAttempts(3);
        properties.getModel().getStreamRecovery().setInitialBackoffMillis(100);
        properties.getModel().getStreamRecovery().setMaxBackoffMillis(500);
        coordinator = new RunRecoveryCoordinator(properties);
    }

    @Test
    void classifiesWrappedInterruptedStreamsAndAppliesBoundedBackoff() {
        var error =
                new IllegalStateException(
                        "wrapper",
                        new ModelStreamInterruptedException(
                                "partial stream", new RuntimeException("closed"), "qwen", "gw"));

        var first = coordinator.decide(error, 1);
        var second = coordinator.decide(error, 2);

        assertThat(first.recoverable()).isTrue();
        assertThat(first.reasonCode()).isEqualTo("MODEL_STREAM_INTERRUPTED");
        assertThat(first.delayMillis()).isBetween(75L, 125L);
        assertThat(second.delayMillis()).isBetween(150L, 250L);
    }

    @Test
    void stopsAfterConfiguredAttemptLimit() {
        var decision = coordinator.decide(new ModelException("unavailable"), 3);

        assertThat(decision.recoverable()).isFalse();
        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("MODEL_REQUEST_FAILED");
    }

    @Test
    void ignoresBusinessFailuresAndDisabledPolicy() {
        assertThat(coordinator.decide(new IllegalArgumentException("bad input"), 1).recoverable())
                .isFalse();

        properties.getModel().getStreamRecovery().setEnabled(false);
        var disabled = new RunRecoveryCoordinator(properties);
        var decision = disabled.decide(new ModelException("unavailable"), 1);
        assertThat(decision.recoverable()).isFalse();
        assertThat(decision.exhausted()).isFalse();
    }

    @Test
    void archiveBoundaryNeverUnwrapsModelCauseIntoBusinessRecovery() {
        var error =
                new io.agentscope.saas.app.workspace.WorkspaceArchiveRejectedException(
                        new ModelException("connection reset"));
        assertThat(coordinator.decide(error, 1).recoverable()).isFalse();
        assertThat(coordinator.decide(error, 3).exhausted()).isFalse();
        assertThat(
                        coordinator
                                .decide(
                                        new io.agentscope.saas.app.workspace
                                                .WorkspaceArchiveDeferredException(
                                                java.util.UUID.randomUUID()),
                                        1)
                                .recoverable())
                .isFalse();
    }

    @Test
    void doesNotRetryPermanentProviderClientErrors() {
        var unauthorized =
                new ModelException(
                        "provider rejected request",
                        OpenAIException.create(401, "invalid token", "auth", "{}"));
        var rateLimited =
                new ModelException(
                        "provider throttled request",
                        OpenAIException.create(429, "slow down", "rate_limit", "{}"));

        assertThat(coordinator.decide(unauthorized, 1).recoverable()).isFalse();
        assertThat(coordinator.decide(rateLimited, 1).recoverable()).isTrue();
        assertThat(
                        coordinator
                                .decide(
                                        new ModelStreamInterruptedException(
                                                "partial then rejected",
                                                unauthorized,
                                                "fixture",
                                                "fixture"),
                                        1)
                                .recoverable())
                .isFalse();
    }

    @Test
    void commitBoundaryWinsEvenWhenWrappedByAModelException() {
        var boundary =
                new io.agentscope.saas.orchestration.TaskResultCommitException(
                        new ModelException("inner transport"));
        var error = new ModelException("outer model wrapper", boundary);
        assertThat(coordinator.decide(error, 1).recoverable()).isFalse();
        assertThat(coordinator.decide(error, 3).exhausted()).isFalse();
    }

    @Test
    void archiveBoundaryWinsBeforeAnyOuterModelClassification() {
        var error =
                new ModelException(
                        "model wrapper",
                        new io.agentscope.saas.app.workspace.WorkspaceArchiveDeferredException(
                                java.util.UUID.randomUUID()));
        assertThat(coordinator.decide(error, 1).recoverable()).isFalse();
    }
}
