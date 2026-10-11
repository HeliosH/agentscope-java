/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.WorkspaceSourceRepository;
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

@Component
public class WorkspaceSourceGcJob {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSourceGcJob.class);
    private final WorkspaceSourceRepository sources;
    private final TransactionOperations admin;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;

    public WorkspaceSourceGcJob(
            WorkspaceSourceRepository sources,
            @Qualifier("adminTransactionOperations") TransactionOperations admin,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties) {
        this.sources = sources;
        this.admin = admin;
        this.stores = stores;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.workspace-source-gc-fixed-delay-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        try {
            collectOnce(OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Workspace source GC deferred ({})", e.getClass().getSimpleName());
        }
    }

    public int collectOnce(OffsetDateTime now) {
        var store = stores.getIfAvailable();
        if (store == null) return 0;
        int count = 0;
        for (var source :
                sources.candidates(
                        now,
                        Math.min(100, Math.max(1, properties.getFileStore().getGcBatchSize())))) {
            UUID token = UUID.randomUUID();
            var claimed =
                    admin.execute(tx -> sources.claim(source, token, now, now.plusSeconds(120)));
            if (claimed == null || claimed != 1) continue;
            String prior = TenantContextHolder.getOrgId();
            try {
                if (!source.backend().equals(store.backend())
                        || !source.objectKey()
                                .equals(
                                        WorkspaceSourceService.prefix(
                                                        source.orgId(), source.userId())
                                                + source.id()
                                                + ".tar"))
                    throw new IllegalStateException("WORKSPACE_SOURCE_GC_SCOPE_MISMATCH");
                TenantContextHolder.setOrgId(source.orgId().toString());
                store.delete(source.orgId(), source.objectKey());
                // A bounded tombstone rescan removes uploads that completed after their timeout.
                var recorded =
                        admin.execute(tx -> sources.collected(source, token, now.plusDays(1)));
                if (recorded != null && recorded == 1) count++;
            } catch (Exception e) {
                admin.executeWithoutResult(
                        tx -> sources.failed(source, token, now.plusSeconds(300)));
                log.warn(
                        "Workspace source deletion deferred id={} ({})",
                        source.id(),
                        e.getClass().getSimpleName());
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return count;
    }
}
