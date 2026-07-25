/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Persistence port for file metadata retention and physical-object deletion queues. */
public interface FileObjectGcRepository {

    List<FileReference> findDeletedFiles(OffsetDateTime deletedBefore, int limit);

    int claimDeletedFile(UUID fileId);

    List<ObjectReference> findFileObjects(UUID fileId);

    void enqueueObject(ObjectReference object, OffsetDateTime createdAt);

    int deleteFileAttachments(UUID fileId);

    int deleteFileVersions(UUID fileId);

    int deleteFile(UUID fileId);

    List<ObjectReference> findPrunableVersions(int maxVersions, int limit);

    int claimPrunableVersion(UUID versionId);

    int deleteFileVersion(UUID versionId);

    List<ObjectReference> findDeletionCandidates(int maxAttempts, int limit);

    int claimDeletion(UUID queueId, OffsetDateTime changedAt);

    long countObjectReferences(UUID orgId, String objectKey);

    int recordDeletion(UUID queueId, String status, String error, OffsetDateTime changedAt);

    record FileReference(UUID id, UUID orgId) {}

    record ObjectReference(UUID id, UUID orgId, String objectKey, String storageBackend) {}
}
