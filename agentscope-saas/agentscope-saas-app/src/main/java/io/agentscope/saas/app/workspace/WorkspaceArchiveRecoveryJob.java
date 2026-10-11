/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceArchiveRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceArchiveRecoveryJob.class);
    private final WorkspaceArchiveRepository archives;
    private final WorkspaceArchiveService service;
    private final SaasProperties properties;

    public WorkspaceArchiveRecoveryJob(
            WorkspaceArchiveRepository archives,
            WorkspaceArchiveService service,
            SaasProperties properties) {
        this.archives = archives;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.file-store.workspace-archive-fixed-delay-seconds:2}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        // Disabling new handovers must not strand already admitted, bounded archive jobs.
        try {
            recoverOnce();
        } catch (RuntimeException error) {
            log.warn("Workspace archive scan deferred ({})", error.getClass().getSimpleName());
        }
    }

    public int recoverOnce() {
        int count = 0;
        for (var job :
                archives.candidates(
                        OffsetDateTime.now(),
                        Math.min(100, Math.max(1, properties.getFileStore().getGcBatchSize())))) {
            String prior = TenantContextHolder.getOrgId();
            TenantContextHolder.setOrgId(job.orgId().toString());
            try {
                if (service.recover(job)) count++;
            } catch (RuntimeException error) {
                log.warn(
                        "Workspace archive item deferred job={} ({})",
                        job.id(),
                        error.getClass().getSimpleName());
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return count;
    }
}
