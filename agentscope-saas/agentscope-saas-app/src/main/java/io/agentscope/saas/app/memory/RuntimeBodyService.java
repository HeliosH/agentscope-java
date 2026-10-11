/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.memory;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository;
import io.agentscope.saas.domain.memory.RuntimeBodyRepository.Body;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository;
import io.agentscope.saas.domain.memory.RuntimeMessageRepository.Scope;
import io.agentscope.saas.storage.FileObject;
import io.agentscope.saas.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Object IO is outside short SQL transactions; metadata publication is separately fenced. */
@Service
public class RuntimeBodyService {
    private final RuntimeBodyRepository bodies;
    private final RuntimeMessageRepository messages;
    private final ObjectProvider<FileObjectStore> stores;
    private final SaasProperties properties;
    private final TransactionTemplate transactions;
    private io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository archiveResults;

    @org.springframework.beans.factory.annotation.Autowired
    void configureArchiveResults(
            io.agentscope.saas.domain.workspace.WorkspaceArchiveRepository repository) {
        archiveResults = repository;
    }

    public RuntimeBodyService(
            RuntimeBodyRepository bodies,
            RuntimeMessageRepository messages,
            ObjectProvider<FileObjectStore> stores,
            SaasProperties properties,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.bodies = bodies;
        this.messages = messages;
        this.stores = stores;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(10);
        var cfg = properties.getRuntimeArchive();
        if (cfg.getInlineMaxBytes() < 256
                || cfg.getInlineMaxBytes() > 33554432
                || cfg.getBodyGraceSeconds() < 60
                || cfg.getBodyGraceSeconds() > 604800
                || cfg.getBodyGcBatchSize() < 1
                || cfg.getBodyGcBatchSize() > 1000
                || cfg.getBodyGcMaxAttempts() < 1
                || cfg.getBodyGcMaxAttempts() > 100
                || cfg.getMaxBodyUserBytes() < 1
                || cfg.getMaxBodyOrgBytes() < cfg.getMaxBodyUserBytes()
                || cfg.getBodyFallbackMaxBytes() < 1
                || cfg.getBodyFallbackMaxBytes() > 1048576
                || cfg.getBodyFallbackMaxUserBytes() < cfg.getBodyFallbackMaxBytes()
                || cfg.getBodyFallbackMaxOrgBytes() < cfg.getBodyFallbackMaxUserBytes())
            throw new IllegalArgumentException("Invalid runtime body policy");
    }

    public boolean offload(long size) {
        return properties.getRuntimeArchive().isLargeBodiesEnabled()
                && size > properties.getRuntimeArchive().getInlineMaxBytes();
    }

    public UUID stage(Scope scope, String json) {
        requireOutsideTransaction();
        FileObjectStore store = stores.getIfAvailable();
        if (store == null || !(store.backend().equals("minio") || store.backend().equals("pg")))
            throw new IllegalStateException("RUNTIME_BODY_BACKEND_UNAVAILABLE");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getRuntimeArchive().getMaxMessageBytes())
            throw new IllegalArgumentException("RUNTIME_ARCHIVE_BYTE_LIMIT");
        UUID id = UUID.randomUUID();
        String key = prefix(scope) + id + ".json";
        OffsetDateTime now = OffsetDateTime.now();
        var body =
                new Body(
                        id,
                        scope.orgId(),
                        scope.userId(),
                        scope.agentId(),
                        scope.sessionId(),
                        key,
                        store.backend(),
                        sha256(bytes),
                        bytes.length,
                        "STAGED",
                        grace(now),
                        null,
                        0);
        transactions.executeWithoutResult(
                tx -> {
                    if (!bodies.lockQuota(scope))
                        throw new IllegalStateException("Runtime body organization not available");
                    if (!messages.lockSession(scope))
                        throw new IllegalStateException("Runtime session is not owned by caller");
                    if (Math.addExact(
                                            Math.addExact(
                                                    bodies.usage(scope.orgId(), scope.userId()),
                                                    archiveResults == null
                                                            ? 0
                                                            : archiveResults.inlineBytes(
                                                                    scope.orgId(), scope.userId())),
                                            bytes.length)
                                    > properties.getRuntimeArchive().getMaxBodyUserBytes()
                            || Math.addExact(
                                            Math.addExact(
                                                    bodies.usage(scope.orgId(), null),
                                                    archiveResults == null
                                                            ? 0
                                                            : archiveResults.inlineBytes(
                                                                    scope.orgId(), null)),
                                            bytes.length)
                                    > properties.getRuntimeArchive().getMaxBodyOrgBytes())
                        throw new IllegalStateException("RUNTIME_BODY_QUOTA_EXCEEDED");
                    bodies.stage(body);
                });
        try {
            store.put(new FileObject(scope.orgId(), key, bytes, "application/json", body.sha256()));
            byte[] verified = store.getBounded(scope.orgId(), key, bytes.length);
            verify(body, verified);
        } catch (Exception error) {
            if (store.backend().equals("minio")
                    && properties.getRuntimeArchive().isBodyFallbackEnabled()
                    && bytes.length <= properties.getRuntimeArchive().getBodyFallbackMaxBytes()) {
                publishFallback(scope, body, json);
                return id;
            }
            throw new IllegalStateException("RUNTIME_BODY_WRITE_FAILED", error);
        }
        OffsetDateTime readyAt = OffsetDateTime.now();
        transactions.executeWithoutResult(
                tx -> {
                    if (bodies.ready(scope, id, readyAt, grace(readyAt)) != 1)
                        throw new IllegalStateException("RUNTIME_BODY_PUBLICATION_FENCED");
                });
        return id;
    }

    private void publishFallback(Scope scope, Body body, String json) {
        OffsetDateTime now = OffsetDateTime.now();
        transactions.executeWithoutResult(
                tx -> {
                    if (!bodies.lockQuota(scope) || !messages.lockSession(scope))
                        throw new IllegalStateException("RUNTIME_BODY_SCOPE_UNAVAILABLE");
                    var cfg = properties.getRuntimeArchive();
                    if (Math.addExact(
                                            bodies.fallbackUsage(scope.orgId(), scope.userId()),
                                            body.sizeBytes())
                                    > cfg.getBodyFallbackMaxUserBytes()
                            || Math.addExact(
                                            bodies.fallbackUsage(scope.orgId(), null),
                                            body.sizeBytes())
                                    > cfg.getBodyFallbackMaxOrgBytes())
                        throw new IllegalStateException("RUNTIME_BODY_FALLBACK_QUOTA_EXCEEDED");
                    if (bodies.ready(scope, body.id(), now, grace(now)) != 1)
                        throw new IllegalStateException("RUNTIME_BODY_PUBLICATION_FENCED");
                    bodies.fallback(body, json);
                });
    }

    /** Exceptional PG results share the ordinary organization/user body quotas. */
    public void admitInline(
            Scope scope,
            long size,
            java.util.function.LongSupplier userExtra,
            java.util.function.LongSupplier orgExtra) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inline result admission requires a transaction");
        if (!bodies.lockQuota(scope) || !messages.lockSession(scope))
            throw new IllegalStateException("RUNTIME_BODY_SCOPE_UNAVAILABLE");
        if (Math.addExact(
                                Math.addExact(
                                        bodies.usage(scope.orgId(), scope.userId()),
                                        userExtra.getAsLong()),
                                size)
                        > properties.getRuntimeArchive().getMaxBodyUserBytes()
                || Math.addExact(
                                Math.addExact(
                                        bodies.usage(scope.orgId(), null), orgExtra.getAsLong()),
                                size)
                        > properties.getRuntimeArchive().getMaxBodyOrgBytes())
            throw new IllegalStateException("RUNTIME_BODY_QUOTA_EXCEEDED");
    }

    /** Called only in the message/cursor transaction; no object IO under this lock. */
    public void attach(Scope scope, UUID id) {
        if (id == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Body reference needs the message transaction");
        if (bodies.attach(scope, id, grace(OffsetDateTime.now())) != 1)
            throw new IllegalStateException("RUNTIME_BODY_PUBLICATION_FENCED");
    }

    public String read(Scope scope, UUID id, long expectedBytes) {
        requireOutsideTransaction();
        Body body =
                bodies.findOwned(scope, id)
                        .filter(value -> value.status().equals("READY"))
                        .orElseThrow(() -> new IllegalStateException("RUNTIME_BODY_UNAVAILABLE"));
        if (body.sizeBytes() != expectedBytes
                || expectedBytes > properties.getRuntimeArchive().getMaxMessageBytes()
                || !body.objectKey().startsWith(prefix(scope)))
            throw new IllegalStateException("RUNTIME_BODY_INTEGRITY_FAILED");
        var fallback = bodies.fallbackPayload(scope, id);
        if (fallback.isPresent()) {
            byte[] bytes = fallback.orElseThrow().getBytes(StandardCharsets.UTF_8);
            verify(body, bytes);
            return fallback.orElseThrow();
        }
        FileObjectStore store = stores.getIfAvailable();
        if (store == null || !body.backend().equals(store.backend()))
            throw new IllegalStateException("RUNTIME_BODY_BACKEND_UNAVAILABLE");
        try {
            byte[] bytes = store.getBounded(scope.orgId(), body.objectKey(), expectedBytes);
            verify(body, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("RUNTIME_BODY_READ_FAILED", error);
        }
    }

    public static String prefix(Scope scope) {
        return "runtime-transcripts/"
                + scope.orgId()
                + "/"
                + scope.userId()
                + "/"
                + scope.agentId()
                + "/"
                + scope.sessionId()
                + "/";
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void verify(Body body, byte[] bytes) {
        if (bytes == null
                || bytes.length != body.sizeBytes()
                || !sha256(bytes).equals(body.sha256()))
            throw new IllegalStateException("RUNTIME_BODY_INTEGRITY_FAILED");
    }

    private OffsetDateTime grace(OffsetDateTime now) {
        return now.plusSeconds(properties.getRuntimeArchive().getBodyGraceSeconds());
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Runtime object IO must not run in a SQL transaction");
    }
}
