/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.sandbox;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.sandbox.SandboxUsageGcRepository;
import io.agentscope.saas.sandbox.SandboxBackendTerminator;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/** Single resource release authority for new shared resources; legacy reconcilers skip these rows. */
@Component
public class SandboxUsageGcJob {
    private static final Logger log = LoggerFactory.getLogger(SandboxUsageGcJob.class);
    private final SandboxUsageGcRepository repository;
    private final TransactionOperations transactions;
    private final PgSandboxUsageGuard guard;
    private final SandboxBackendTerminator terminator;
    private final SaasProperties properties;

    public SandboxUsageGcJob(
            SandboxUsageGcRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            PgSandboxUsageGuard guard,
            SandboxBackendTerminator terminator,
            SaasProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.guard = guard;
        this.terminator = terminator;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.sandbox.reconciliation-fixed-delay-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        if (!properties.getSandbox().isEnabled()
                || !properties.getSandbox().isReconciliationEnabled()) return;
        try {
            collectOnce();
        } catch (RuntimeException error) {
            log.warn("Shared sandbox cleanup failed ({})", error.getClass().getSimpleName());
        }
    }

    public int collectOnce() {
        var cfg = properties.getSandbox();
        var stale =
                OffsetDateTime.now()
                        .minusSeconds(Math.max(0, cfg.getReconciliationActiveGraceSeconds()));
        int attempts = Math.max(1, cfg.getBackendReleaseMaxAttempts()), released = 0;
        for (var candidate :
                repository.candidates(
                        guard.deploymentKey(),
                        stale,
                        attempts,
                        Math.min(500, Math.max(1, cfg.getReconciliationBatchSize())))) {
            UUID token = UUID.randomUUID();
            var claimed =
                    transactions.execute(
                            tx ->
                                    repository.claim(
                                            candidate.id(),
                                            token,
                                            stale,
                                            OffsetDateTime.now().plusSeconds(60),
                                            attempts));
            if (claimed.isEmpty()) continue;
            var resource = claimed.orElseThrow();
            boolean success = resource.externalId() == null;
            if (!success) {
                try {
                    success =
                            terminator
                                    .terminate(resource.providerId(), resource.externalId())
                                    .succeeded();
                } catch (RuntimeException failed) {
                    success = false;
                }
            }
            boolean result = success;
            Boolean finished =
                    transactions.execute(tx -> repository.finish(resource.id(), token, result));
            if (result && Boolean.TRUE.equals(finished)) released++;
        }
        return released;
    }
}
