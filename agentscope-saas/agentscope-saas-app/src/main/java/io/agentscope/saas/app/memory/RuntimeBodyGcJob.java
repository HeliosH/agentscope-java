/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.memory;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
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

/** Fenced orphan reclamation, independent of user-file catalogs and active sandboxes. */
@Component
public class RuntimeBodyGcJob {
    private static final Logger log = LoggerFactory.getLogger(RuntimeBodyGcJob.class);
    private final RuntimeBodyRepository bodies;
    private final TransactionOperations admin;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;

    public RuntimeBodyGcJob(
            RuntimeBodyRepository bodies,
            @Qualifier("adminTransactionOperations") TransactionOperations admin,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties) {
        this.bodies = bodies;
        this.admin = admin;
        this.stores = stores;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${saas.runtime-archive.body-gc-fixed-delay-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void scheduled() {
        if (!properties.getRuntimeArchive().isBodyGcEnabled()) return;
        try {
            collectOnce(OffsetDateTime.now());
        } catch (RuntimeException error) {
            log.warn("Runtime body GC scan failed ({})", error.getClass().getSimpleName());
        }
    }

    public int collectOnce(OffsetDateTime now) {
        FileObjectStore store = stores.getIfAvailable();
        if (store == null) return 0;
        var cfg = properties.getRuntimeArchive();
        var candidates =
                bodies.candidates(now, cfg.getBodyGcMaxAttempts(), cfg.getBodyGcBatchSize());
        int count = 0;
        for (var body : candidates) {
            UUID token = UUID.randomUUID();
            Integer claimed =
                    admin.execute(
                            tx ->
                                    bodies.claim(
                                            body.id(),
                                            token,
                                            now,
                                            now.plusSeconds(120),
                                            cfg.getBodyGcMaxAttempts()));
            if (claimed == null || claimed != 1) continue;
            // Recheck in a new statement after the claim's commit, not its older MVCC snapshot.
            if (bodies.references(body.id()) > 0) {
                admin.executeWithoutResult(
                        tx ->
                                bodies.retain(
                                        body.id(),
                                        token,
                                        now.plusSeconds(cfg.getBodyGraceSeconds())));
                continue;
            }
            String prior = TenantContextHolder.getOrgId();
            try {
                var scope =
                        new Scope(
                                body.orgId(),
                                body.userId(),
                                body.agentId(),
                                body.sessionId(),
                                "",
                                "");
                if (!body.backend().equals(store.backend())
                        || !body.objectKey().startsWith(RuntimeBodyService.prefix(scope)))
                    throw new IllegalStateException("Runtime body GC backend or key mismatch");
                TenantContextHolder.setOrgId(body.orgId().toString());
                store.delete(body.orgId(), body.objectKey());
                // Keep a periodically rechecked tombstone: a timed-out upload may finish after
                // deletion.
                Integer recorded =
                        admin.execute(tx -> bodies.collected(body.id(), token, now.plusDays(1)));
                if (recorded != null && recorded == 1) count++;
            } catch (Exception error) {
                admin.executeWithoutResult(
                        tx -> bodies.failed(body.id(), token, now.plusSeconds(300)));
                log.warn("Runtime body GC deletion failed ({})", error.getClass().getSimpleName());
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return count;
    }
}
