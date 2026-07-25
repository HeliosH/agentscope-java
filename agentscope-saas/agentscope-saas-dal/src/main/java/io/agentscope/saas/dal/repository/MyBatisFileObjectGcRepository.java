/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.admin.FileGcReferenceData;
import io.agentscope.saas.dal.mybatis.admin.FileObjectGcMapper;
import io.agentscope.saas.domain.workspace.FileObjectGcRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis implementation of file-retention and object-deletion queue persistence. */
@Repository
public class MyBatisFileObjectGcRepository implements FileObjectGcRepository {

    private final FileObjectGcMapper mapper;

    public MyBatisFileObjectGcRepository(FileObjectGcMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<FileReference> findDeletedFiles(OffsetDateTime deletedBefore, int limit) {
        return mapper.findDeletedFiles(deletedBefore, limit).stream()
                .map(data -> new FileReference(data.id(), data.orgId()))
                .toList();
    }

    @Override
    public int claimDeletedFile(UUID fileId) {
        return mapper.claimDeletedFile(fileId);
    }

    @Override
    public List<ObjectReference> findFileObjects(UUID fileId) {
        return mapper.findFileObjects(fileId).stream().map(this::toObject).toList();
    }

    @Override
    public void enqueueObject(ObjectReference object, OffsetDateTime createdAt) {
        int inserted =
                mapper.enqueueObject(
                        UUID.randomUUID(),
                        object.orgId(),
                        object.objectKey(),
                        object.storageBackend(),
                        createdAt);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "File object was not enqueued for deletion: " + object.objectKey());
        }
    }

    @Override
    public int deleteFileAttachments(UUID fileId) {
        return mapper.deleteFileAttachments(fileId);
    }

    @Override
    public int deleteFileVersions(UUID fileId) {
        return mapper.deleteFileVersions(fileId);
    }

    @Override
    public int deleteFile(UUID fileId) {
        return mapper.deleteFile(fileId);
    }

    @Override
    public List<ObjectReference> findPrunableVersions(int maxVersions, int limit) {
        return mapper.findPrunableVersions(maxVersions, limit).stream()
                .map(this::toObject)
                .toList();
    }

    @Override
    public int claimPrunableVersion(UUID versionId) {
        return mapper.claimPrunableVersion(versionId);
    }

    @Override
    public int deleteFileVersion(UUID versionId) {
        return mapper.deleteFileVersion(versionId);
    }

    @Override
    public List<ObjectReference> findDeletionCandidates(int maxAttempts, int limit) {
        return mapper.findDeletionCandidates(maxAttempts, limit).stream()
                .map(this::toObject)
                .toList();
    }

    @Override
    public int claimDeletion(UUID queueId, OffsetDateTime changedAt) {
        return mapper.claimDeletion(queueId, changedAt);
    }

    @Override
    public long countObjectReferences(UUID orgId, String objectKey) {
        return mapper.countObjectReferences(orgId, objectKey);
    }

    @Override
    public int recordDeletion(UUID queueId, String status, String error, OffsetDateTime changedAt) {
        return mapper.recordDeletion(queueId, status, error, changedAt);
    }

    private ObjectReference toObject(FileGcReferenceData data) {
        return new ObjectReference(
                data.id(), data.orgId(), data.objectKey(), data.storageBackend());
    }
}
