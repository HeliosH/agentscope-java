/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.dal.repository;

import io.agentscope.saas.dal.mybatis.tenant.WorkspaceCatalogMapper;
import io.agentscope.saas.domain.model.FileAttachmentEntity;
import io.agentscope.saas.domain.model.FileEntity;
import io.agentscope.saas.domain.repository.FileAttachmentRepository;
import io.agentscope.saas.domain.repository.FileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MyBatis adapter for the Workspace File aggregate. */
@Repository
public class MyBatisWorkspaceCatalogRepository implements FileRepository, FileAttachmentRepository {

    private final WorkspaceCatalogMapper mapper;

    public MyBatisWorkspaceCatalogRepository(WorkspaceCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<FileEntity> findByOrgIdAndUserIdAndLogicalPath(
            UUID orgId, UUID userId, String logicalPath) {
        return first(mapper.findFileByPath(orgId, userId, logicalPath));
    }

    @Override
    public Optional<FileEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId) {
        return first(mapper.findOwnedFile(id, orgId, userId));
    }

    @Override
    public List<FileEntity> findByOrgIdAndUserIdAndStatusOrderByLogicalPathAsc(
            UUID orgId, UUID userId, String status) {
        return mapper.findFilesByStatus(orgId, userId, status);
    }

    @Override
    public Optional<FileEntity> lockByOrgUserPath(UUID orgId, UUID userId, String logicalPath) {
        return first(mapper.lockFileByPath(orgId, userId, logicalPath));
    }

    @Override
    public FileEntity save(FileEntity file) {
        if (mapper.updateFile(file) == 0) {
            requireOne(mapper.insertFile(file), "insert File " + file.getId());
        }
        return file;
    }

    @Override
    public List<FileAttachmentEntity> findByOrgIdAndUserIdAndSessionIdOrderByCreatedAtDesc(
            UUID orgId, UUID userId, UUID sessionId) {
        return mapper.findSessionAttachments(orgId, userId, sessionId);
    }

    @Override
    public List<FileAttachmentEntity> findByOrgIdAndUserIdAndMessageIdOrderByCreatedAtDesc(
            UUID orgId, UUID userId, UUID messageId) {
        return mapper.findMessageAttachments(orgId, userId, messageId);
    }

    @Override
    public FileAttachmentEntity save(FileAttachmentEntity attachment) {
        requireOne(
                mapper.insertAttachment(attachment), "insert FileAttachment " + attachment.getId());
        return attachment;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
