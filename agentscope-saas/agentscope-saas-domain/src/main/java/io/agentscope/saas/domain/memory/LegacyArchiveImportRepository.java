/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Progress and provenance for converting immutable JSONL evidence, not executable checkpoints. */
public interface LegacyArchiveImportRepository {
    record Owner(UUID orgId, UUID userId, UUID agentId, UUID sessionId) {}

    record Job(
            UUID id,
            UUID orgId,
            UUID userId,
            UUID agentId,
            UUID sessionId,
            long sessionGeneration,
            int formatVersion,
            UUID sourceVersionId,
            String sourceSha256,
            long sourceBytes,
            long byteOffset,
            long lineNo,
            long importedMessages,
            long skippedEntries,
            String digestChain,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        public Owner owner() {
            return new Owner(orgId, userId, agentId, sessionId);
        }

        public String archiveKey() {
            return "legacy:" + id;
        }
    }

    record Mapping(
            UUID jobId,
            UUID orgId,
            UUID userId,
            long lineNo,
            long byteStart,
            long byteEnd,
            String lineSha256,
            String sourceEntryId,
            String parentEntryId,
            String toolCallId,
            String entryType,
            boolean syntheticIdentity,
            String messageId,
            Long archiveSeq,
            String contentHash) {}

    record Proof(long entries, long converted, long lastByteEnd) {}

    void create(Job job);

    Optional<Job> findBySource(Owner owner, UUID versionId);

    Optional<Job> find(Owner owner, UUID id);

    Optional<Job> lock(Owner owner, UUID id);

    void deleteSession(UUID orgId, UUID userId, UUID sessionId);

    void insert(Mapping mapping);

    boolean advance(
            Job expected,
            long offset,
            long lines,
            long imported,
            long skipped,
            String digestChain,
            boolean complete);

    List<Mapping> mappings(Owner owner, UUID jobId, long afterLine, int limit);

    Proof proof(Owner owner, UUID jobId);
}
