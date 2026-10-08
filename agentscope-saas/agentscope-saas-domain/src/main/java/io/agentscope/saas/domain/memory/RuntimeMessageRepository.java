/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ordered source ledger; transactions and serialization belong to the application layer. */
public interface RuntimeMessageRepository {
    record Scope(
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            String agentLabel,
            String sessionKey) {}

    record Stream(UUID id, Scope scope, long lastSeq, String lastMessageId) {}

    record Message(
            UUID streamId,
            long seq,
            String messageId,
            String parentMessageId,
            UUID sourceRunId,
            UUID sourceAgentRunId,
            String role,
            String payloadJson,
            String contentHash,
            long contentBytes,
            OffsetDateTime createdAt,
            UUID bodyId) {}

    record Identity(long seq, String messageId, String contentHash) {}

    record Hit(String agentLabel, String sessionKey, long seq, String role, String preview) {}

    boolean lockSession(Scope scope);

    void ensureStream(UUID id, Scope scope);

    Stream lockStream(Scope scope);

    Optional<Identity> findIdentity(UUID streamId, Scope scope, String messageId);

    List<Identity> findIdentities(UUID streamId, Scope scope, List<String> messageIds);

    /** Metadata lookup deliberately leaves payloadJson null, including for legacy inline bodies. */
    List<Message> findMessageMetadata(UUID streamId, Scope scope, List<String> messageIds);

    Optional<Message> findOwnedSource(Scope scope, String messageId, String hash);

    void insert(Scope scope, Message message);

    void advance(Scope scope, UUID streamId, long seq, String lastMessageId);

    Optional<Stream> findStream(
            UUID orgId, UUID userId, UUID agentId, String agentLabel, String sessionKey);

    Optional<Stream> findStream(Scope scope);

    List<Message> window(
            Scope scope, UUID streamId, Long after, Long before, int limit, long maxBytes);

    boolean hasBeyond(Scope scope, UUID streamId, Long after, Long before);

    List<Hit> search(
            UUID orgId, UUID userId, UUID agentId, String agentLabel, String pattern, int limit);

    List<Stream> list(UUID orgId, UUID userId, UUID agentId, String agentLabel, int limit);

    void deleteSession(UUID orgId, UUID userId, UUID sessionId);
}
