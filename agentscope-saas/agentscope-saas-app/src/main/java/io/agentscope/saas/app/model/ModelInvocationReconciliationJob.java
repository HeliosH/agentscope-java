/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.saas.app.config.SaasProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, retryable receipt reconciliation; owner locks make concurrent sweepers idempotent. */
@Component
public class ModelInvocationReconciliationJob {
    private static final Logger log =
            LoggerFactory.getLogger(ModelInvocationReconciliationJob.class);
    private final ModelInvocationLedgerService ledger;
    private final SaasProperties properties;

    public ModelInvocationReconciliationJob(
            ModelInvocationLedgerService ledger, SaasProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${saas.model.invocations.reconcile-fixed-delay-seconds:60}000")
    public void reconcile() {
        var config = properties.getModel().getInvocations();
        if (!config.isReconciliationEnabled()) return;
        try {
            int count =
                    ledger.reconcileExpired(
                            OffsetDateTime.now(ZoneOffset.UTC)
                                    .minusSeconds(config.getReconciliationGraceSeconds()),
                            config.getReconciliationBatchSize());
            if (count > 0)
                log.info("Reconciled {} abandoned model invocations with estimated usage", count);
        } catch (RuntimeException error) {
            log.warn(
                    "Model invocation reconciliation scan failed: {}",
                    error.getClass().getSimpleName());
        }
    }
}
