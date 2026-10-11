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
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
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

/** Drain bounded outage bodies without replacing IDs or replaying execution. */
@Component
public class RuntimeBodyRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(RuntimeBodyRecoveryJob.class);
    private final RuntimeBodyRepository bodies;
    private final TransactionOperations admin;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;

    public RuntimeBodyRecoveryJob(
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
            fixedDelayString = "${saas.runtime-archive.body-fallback-recovery-delay-seconds:10}",
            timeUnit = TimeUnit.SECONDS,
            scheduler = "runtimeBodyRecoveryScheduler")
    public void scheduled() {
        try {
            recoverOnce(OffsetDateTime.now());
        } catch (RuntimeException error) {
            log.warn("Runtime body recovery scan failed ({})", error.getClass().getSimpleName());
        }
    }

    public int recoverOnce(OffsetDateTime now) {
        FileObjectStore store = stores.getIfAvailable();
        if (store == null || !store.backend().equals("minio")) return 0;
        int recovered = 0;
        long scanDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (var body :
                bodies.fallbackCandidates(
                        now, properties.getRuntimeArchive().getBodyGcBatchSize())) {
            if (System.nanoTime() >= scanDeadline) break;
            if (!body.backend().equals(store.backend())) continue;
            UUID token = UUID.randomUUID();
            OffsetDateTime wallNow = OffsetDateTime.now();
            OffsetDateTime claimAt = now.isAfter(wallNow) ? now : wallNow;
            OffsetDateTime lease = claimAt.plusSeconds(120);
            Integer claimed =
                    admin.execute(
                            tx -> {
                                if (bodies.protectFallback(body.id(), claimAt, lease) != 1)
                                    return 0;
                                return bodies.claimFallback(body.id(), token, claimAt, lease);
                            });
            if (claimed == null || claimed != 1) continue;
            String prior = TenantContextHolder.getOrgId();
            try {
                TenantContextHolder.setOrgId(body.orgId().toString());
                Scope scope =
                        new Scope(
                                body.orgId(),
                                body.userId(),
                                body.agentId(),
                                body.sessionId(),
                                "",
                                "");
                if (!body.objectKey().startsWith(RuntimeBodyService.prefix(scope)))
                    throw new IllegalStateException("RUNTIME_BODY_INTEGRITY_FAILED");
                String payload = bodies.fallbackPayload(scope, body.id()).orElseThrow();
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                RuntimeBodyService.verify(body, bytes);
                store.put(
                        new FileObject(
                                body.orgId(),
                                body.objectKey(),
                                bytes,
                                "application/json",
                                body.sha256()));
                RuntimeBodyService.verify(
                        body, store.getBounded(body.orgId(), body.objectKey(), body.sizeBytes()));
                Integer completed =
                        admin.execute(
                                tx -> bodies.offloaded(body.id(), token, OffsetDateTime.now()));
                if (completed != null && completed == 1) recovered++;
            } catch (Exception error) {
                admin.executeWithoutResult(
                        tx ->
                                bodies.retryFallback(
                                        body.id(), token, OffsetDateTime.now().plusSeconds(30)));
                log.warn("Runtime body offload deferred ({})", error.getClass().getSimpleName());
                // A storage outage must not multiply IO failures across the entire candidate batch.
                break;
            } finally {
                if (prior == null) TenantContextHolder.clear();
                else TenantContextHolder.setOrgId(prior);
            }
        }
        return recovered;
    }
}
