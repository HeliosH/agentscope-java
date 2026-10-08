/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Intent;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Publication;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short reservation/commit transactions; callers may not carry transaction locks across IO. */
@Service
public class FilePublicationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(FilePublicationCoordinator.class);
    private final FilePublicationRepository publications;
    private final TransactionTemplate shortTransaction;
    private final TransactionTemplate outsideTransaction;
    private final SaasProperties properties;

    public FilePublicationCoordinator(
            FilePublicationRepository publications,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            SaasProperties properties) {
        this.publications = publications;
        this.properties = properties;
        if (properties.getFileStore().getPublicationLeaseSeconds() < 1)
            throw new IllegalArgumentException("Invalid file publication lease");
        var cfg = properties.getFileStore();
        if (cfg.getPublicationRecoveryMaxAttempts() < 1
                || cfg.getPublicationRecoveryRetrySeconds() < 1
                || cfg.getPublicationRecoveryDeadlineSeconds() <= cfg.getPublicationLeaseSeconds())
            throw new IllegalArgumentException("Invalid file publication recovery budget");
        shortTransaction = new TransactionTemplate(manager);
        shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public FileCatalogService.FileRecord publish(
            Supplier<Publication> admission,
            Consumer<Publication> transfer,
            Function<Publication, FileCatalogService.FileRecord> catalog) {
        return publish(admission, p -> null, transfer, catalog);
    }

    public FileCatalogService.FileRecord publish(
            Supplier<Publication> admission,
            Function<Publication, Intent> intentFactory,
            Consumer<Publication> transfer,
            Function<Publication, FileCatalogService.FileRecord> catalog) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("File publication must start outside a transaction");
        return outsideTransaction.execute(
                ignored -> {
                    AtomicBoolean recoveryEnabled = new AtomicBoolean();
                    Publication publication =
                            Objects.requireNonNull(
                                    shortTransaction.execute(
                                            tx -> {
                                                Publication p = admission.get();
                                                publications.stage(p);
                                                Intent intent = intentFactory.apply(p);
                                                if (intent != null) {
                                                    boolean recoverable =
                                                            properties
                                                                            .getFileStore()
                                                                            .isPublicationRecoveryEnabled()
                                                                    && (p.runId() == null
                                                                            || intent.execution()
                                                                                    != null);
                                                    publications.saveIntent(
                                                            p,
                                                            intent,
                                                            recoverable,
                                                            OffsetDateTime.now()
                                                                    .plusSeconds(
                                                                            properties
                                                                                    .getFileStore()
                                                                                    .getPublicationRecoveryDeadlineSeconds()));
                                                    recoveryEnabled.set(recoverable);
                                                }
                                                return p;
                                            }));
                    boolean transferred = false;
                    try {
                        transfer.accept(publication);
                        transferred = true;
                        shortTransaction.executeWithoutResult(
                                tx -> {
                                    if (publications.stored(
                                                    publication.orgId(),
                                                    publication.userId(),
                                                    publication.id(),
                                                    OffsetDateTime.now())
                                            != 1)
                                        throw new IllegalStateException("FILE_PUBLICATION_EXPIRED");
                                });
                        return shortTransaction.execute(
                                tx -> {
                                    // Reset locks session before publication; catalog acquisition
                                    // must follow that order, with all writes still uncommitted.
                                    FileCatalogService.FileRecord receipt =
                                            catalog.apply(publication);
                                    Publication current =
                                            publications
                                                    .lockOwned(
                                                            publication.orgId(),
                                                            publication.userId(),
                                                            publication.id())
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "FILE_PUBLICATION_MISSING"));
                                    if (!"STORED".equals(current.status())
                                            || !current.leaseUntil().isAfter(OffsetDateTime.now()))
                                        throw new IllegalStateException("FILE_PUBLICATION_EXPIRED");
                                    if (!current.objectKey().equals(receipt.objectKey())
                                            || !current.backend().equals(receipt.storageBackend()))
                                        throw new IllegalStateException(
                                                "FILE_PUBLICATION_SOURCE_CHANGED");
                                    if (publications.published(
                                                    current.orgId(),
                                                    current.userId(),
                                                    current.id(),
                                                    receipt.versionId(),
                                                    OffsetDateTime.now())
                                            != 1)
                                        throw new IllegalStateException("FILE_PUBLICATION_EXPIRED");
                                    return receipt;
                                });
                    } catch (RuntimeException error) {
                        if (transferred
                                && recoveryEnabled.get()
                                && (error instanceof DataAccessException
                                        || error instanceof TransactionException)) {
                            try {
                                shortTransaction.executeWithoutResult(
                                        tx ->
                                                publications.deferRecovery(
                                                        publication.orgId(),
                                                        publication.userId(),
                                                        publication.id(),
                                                        null,
                                                        OffsetDateTime.now(),
                                                        OffsetDateTime.now()
                                                                .plusSeconds(
                                                                        properties
                                                                                .getFileStore()
                                                                                .getPublicationRecoveryRetrySeconds())));
                            } catch (RuntimeException unavailable) {
                                log.warn(
                                        "File publication recovery deferred to expiry ({})",
                                        unavailable.getClass().getSimpleName());
                            }
                            throw error;
                        }
                        try {
                            shortTransaction.executeWithoutResult(
                                    tx ->
                                            publications.abort(
                                                    publication.orgId(),
                                                    publication.userId(),
                                                    publication.id(),
                                                    publication.leaseUntil()));
                        } catch (RuntimeException unavailable) {
                            // The durable reservation remains discoverable by expiry after DB
                            // recovery.
                            log.warn(
                                    "File publication abort deferred ({})",
                                    unavailable.getClass().getSimpleName());
                        }
                        throw error;
                    }
                });
    }

    public boolean claimRecovery(Publication p, UUID token, Runnable admission) {
        return Boolean.TRUE.equals(
                shortTransaction.execute(
                        tx -> {
                            admission.run();
                            return publications.reclaimRecovery(
                                            p.orgId(),
                                            p.userId(),
                                            p.id(),
                                            token,
                                            OffsetDateTime.now(),
                                            leaseUntil(),
                                            properties
                                                    .getFileStore()
                                                    .getPublicationRecoveryMaxAttempts())
                                    == 1;
                        }));
    }

    public FileCatalogService.FileRecord commitRecovery(
            Publication p,
            UUID token,
            Function<Publication, FileCatalogService.FileRecord> catalog) {
        return shortTransaction.execute(
                tx -> {
                    var receipt = catalog.apply(p);
                    var current =
                            publications.lockOwned(p.orgId(), p.userId(), p.id()).orElseThrow();
                    if (!"STORED".equals(current.status())
                            || !current.leaseUntil().isAfter(OffsetDateTime.now())
                            || publications.publishRecovered(
                                            p.orgId(),
                                            p.userId(),
                                            p.id(),
                                            token,
                                            receipt.versionId(),
                                            OffsetDateTime.now())
                                    != 1)
                        throw new IllegalStateException("FILE_PUBLICATION_RECOVERY_LEASE_LOST");
                    if (!p.objectKey().equals(receipt.objectKey())
                            || !p.backend().equals(receipt.storageBackend()))
                        throw new IllegalStateException("FILE_PUBLICATION_SOURCE_CHANGED");
                    return receipt;
                });
    }

    public Intent intent(Publication p) {
        return publications.intent(p.orgId(), p.userId(), p.id()).orElse(null);
    }

    public void requireExecution(Publication p, Intent i) {
        if (i != null
                && i.execution() != null
                && !publications.lockExecution(p, i, OffsetDateTime.now()))
            throw new io.agentscope.saas.domain.orchestration.SessionExecutionRevokedException();
    }

    public long reserved(UUID org, UUID user, UUID excluding) {
        return publications.reserved(org, user, excluding, OffsetDateTime.now());
    }

    public void requireIdlePath(UUID org, UUID user, String path) {
        if (publications.pathBusy(org, user, path, OffsetDateTime.now()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "File publication already in progress");
    }

    public OffsetDateTime leaseUntil() {
        return OffsetDateTime.now()
                .plusSeconds(properties.getFileStore().getPublicationLeaseSeconds());
    }
}
