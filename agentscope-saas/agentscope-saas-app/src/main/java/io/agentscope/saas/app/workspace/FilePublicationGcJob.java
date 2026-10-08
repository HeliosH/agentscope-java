/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.FilePublicationRepository;
import io.agentscope.saas.storage.FileObjectStore;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/** Fenced orphan deletion; tombstones also cover late completion of expired uploads. */
@Component
public class FilePublicationGcJob {
    private static final Logger log = LoggerFactory.getLogger(FilePublicationGcJob.class);
    private final FilePublicationRepository publications;
    private final TransactionOperations admin;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;

    public FilePublicationGcJob(
            FilePublicationRepository publications,
            @Qualifier("adminTransactionOperations") TransactionOperations admin,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties) {
        this.publications = publications;
        this.admin = admin;
        this.stores = stores;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.publication-gc-fixed-delay-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        if (!properties.getFileStore().isEnabled() || !properties.getFileStore().isGcEnabled())
            return;
        try {
            collectOnce(OffsetDateTime.now());
        } catch (RuntimeException error) {
            log.warn("File publication GC scan failed ({})", error.getClass().getSimpleName());
        }
    }

    public int collectOnce(OffsetDateTime now) {
        FileObjectStore store = stores.getIfAvailable();
        if (store == null) return 0;
        var cfg = properties.getFileStore();
        int count = 0;
        for (var p : publications.candidates(now, cfg.getGcMaxAttempts(), cfg.getGcBatchSize())) {
            UUID token = UUID.randomUUID();
            Integer claimed =
                    admin.execute(
                            tx ->
                                    publications.claim(
                                            p.id(),
                                            token,
                                            now,
                                            now.plusSeconds(120),
                                            cfg.getGcMaxAttempts()));
            if (claimed == null || claimed != 1) continue;
            String prior = TenantContextHolder.getOrgId();
            try {
                if (p.ownsObject()) {
                    if (publications.references(p.id()) > 0)
                        throw new IllegalStateException("Publication object still referenced");
                    String suffix =
                            "/org="
                                    + p.orgId()
                                    + "/user="
                                    + p.userId()
                                    + "/publications/"
                                    + p.id()
                                    + "-"
                                    + p.sha256().substring(0, 16);
                    if (!p.backend().equals(store.backend()) || !p.objectKey().endsWith(suffix))
                        throw new IllegalStateException("Publication GC backend or key mismatch");
                    TenantContextHolder.setOrgId(p.orgId().toString());
                    store.delete(p.orgId(), p.objectKey());
                }
                Integer collected =
                        admin.execute(tx -> publications.collected(p.id(), token, now.plusDays(1)));
                if (collected != null && collected == 1) count++;
            } catch (Exception error) {
                admin.executeWithoutResult(
                        tx -> publications.failed(p.id(), token, now.plusSeconds(300)));
                log.warn(
                        "File publication GC deletion deferred ({})",
                        error.getClass().getSimpleName());
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return count;
    }
}
