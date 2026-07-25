/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.FileEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for logical workspace files. */
public interface FileRepository {

    Optional<FileEntity> findByOrgIdAndUserIdAndLogicalPath(
            UUID orgId, UUID userId, String logicalPath);

    Optional<FileEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId);

    List<FileEntity> findByOrgIdAndUserIdAndStatusOrderByLogicalPathAsc(
            UUID orgId, UUID userId, String status);

    Optional<FileEntity> lockByOrgUserPath(UUID orgId, UUID userId, String logicalPath);

    FileEntity save(FileEntity file);

    default FileEntity saveAndFlush(FileEntity file) {
        return save(file);
    }
}
