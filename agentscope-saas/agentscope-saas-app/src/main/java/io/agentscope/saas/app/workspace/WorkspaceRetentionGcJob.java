/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend;
import io.agentscope.saas.domain.workspace.WorkspaceRetentionRepository;
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
public class WorkspaceRetentionGcJob {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceRetentionGcJob.class);
    private final WorkspaceRetentionRepository repository;
    private final TransactionOperations admin;
    private final ObjectProvider<WorkspaceRetentionBackend> backends;
    private io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository archiveResults;
    private io.agentscope.saas.app.config.SaasProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    void configureResultCleanup(
            io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository repository,
            io.agentscope.saas.app.config.SaasProperties properties) {
        archiveResults = repository;
        this.properties = properties;
    }

    public WorkspaceRetentionGcJob(
            WorkspaceRetentionRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations admin,
            ObjectProvider<WorkspaceRetentionBackend> backends) {
        this.repository = repository;
        this.admin = admin;
        this.backends = backends;
    }

    @Scheduled(
            fixedDelayString =
                    "${saas.file-store.workspace-provider-retention-gc-fixed-delay-seconds:10}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        try {
            collectOnce(OffsetDateTime.now());
        } catch (RuntimeException error) {
            log.warn("Workspace retention cleanup deferred ({})", error.getClass().getSimpleName());
        }
    }

    public int collectOnce(OffsetDateTime now) {
        if (archiveResults != null)
            admin.executeWithoutResult(
                    tx ->
                            archiveResults.purgeInline(
                                    now.minusSeconds(
                                            properties.getRuntimeArchive().getBodyGraceSeconds()),
                                    100));
        var backend = backends.getIfAvailable();
        if (backend == null) return 0;
        int collected = 0;
        for (var value : repository.due(now, 100)) {
            if (!backend.providerId().equals(value.providerId())) continue;
            UUID token = UUID.randomUUID();
            if (!Integer.valueOf(1)
                    .equals(
                            admin.execute(
                                    tx ->
                                            repository.claim(
                                                    value, token, now, now.plusSeconds(120)))))
                continue;
            try {
                backend.terminate(value.resourceId());
                admin.executeWithoutResult(tx -> repository.cleaned(value, token));
                collected++;
            } catch (Exception error) {
                log.warn(
                        "Workspace provider retention cleanup deferred source={} ({})",
                        value.sourceId(),
                        error.getClass().getSimpleName());
            }
        }
        return collected;
    }
}
