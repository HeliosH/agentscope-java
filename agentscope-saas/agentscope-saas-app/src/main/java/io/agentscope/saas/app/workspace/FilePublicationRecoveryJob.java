/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles catalog facts only; never calls a model, a tool or a sandbox. */
@Component
public class FilePublicationRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(FilePublicationRecoveryJob.class);
    private final FilePublicationRepository publications;
    private final FileCatalogService catalog;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;

    public FilePublicationRecoveryJob(
            FilePublicationRepository publications,
            FileCatalogService catalog,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.publications = publications;
        this.catalog = catalog;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.publication-recovery-fixed-delay-seconds:5}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        if (!properties.getFileStore().isEnabled()
                || !properties.getFileStore().isPublicationRecoveryEnabled()) return;
        try {
            recoverOnce();
        } catch (RuntimeException error) {
            log.warn(
                    "File publication recovery scan deferred ({})",
                    error.getClass().getSimpleName());
        }
    }

    public int recoverOnce() {
        var cfg = properties.getFileStore();
        int count = 0;
        for (var p :
                publications.recoveryCandidates(
                        OffsetDateTime.now(),
                        cfg.getPublicationRecoveryMaxAttempts(),
                        Math.max(1, cfg.getGcBatchSize()))) {
            UUID token = UUID.randomUUID();
            String prior = TenantContextHolder.getOrgId();
            TenantContextHolder.setOrgId(p.orgId().toString());
            try {
                if (catalog.recoverPublication(p, token)) count++;
            } catch (RuntimeException error) {
                try {
                    var state = publications.recovery(p.orgId(), p.userId(), p.id()).orElse(null);
                    boolean retryable =
                            error instanceof FilePublicationRetryableException
                                    || error instanceof DataAccessException
                                    || error instanceof TransactionException;
                    if (retryable
                            && state != null
                            && state.recoveryAttempts() < cfg.getPublicationRecoveryMaxAttempts()) {
                        UUID owner = token.equals(state.recoveryToken()) ? token : null;
                        transactions.executeWithoutResult(
                                tx ->
                                        publications.deferRecovery(
                                                p.orgId(),
                                                p.userId(),
                                                p.id(),
                                                owner,
                                                OffsetDateTime.now(),
                                                OffsetDateTime.now()
                                                        .plusSeconds(
                                                                cfg
                                                                        .getPublicationRecoveryRetrySeconds())));
                    } else {
                        transactions.executeWithoutResult(
                                tx -> {
                                    if (state != null && token.equals(state.recoveryToken()))
                                        publications.abandonRecovery(
                                                p.orgId(),
                                                p.userId(),
                                                p.id(),
                                                token,
                                                OffsetDateTime.now());
                                    else
                                        publications.abort(
                                                p.orgId(),
                                                p.userId(),
                                                p.id(),
                                                OffsetDateTime.now());
                                });
                    }
                } catch (RuntimeException unavailable) {
                    log.warn(
                            "File publication recovery settlement deferred ({})",
                            unavailable.getClass().getSimpleName());
                }
                log.warn(
                        "File publication recovery item deferred ({})",
                        error.getClass().getSimpleName());
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return count;
    }
}
