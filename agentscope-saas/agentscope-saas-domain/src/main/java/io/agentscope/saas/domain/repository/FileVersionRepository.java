/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.FileVersionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain persistence port for immutable workspace file versions. */
public interface FileVersionRepository {

    Optional<FileVersionEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<FileVersionEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId);

    Optional<FileVersionEntity> findFirstByFileIdOrderByVersionNoDesc(UUID fileId);

    List<FileVersionEntity> findByFileIdAndOrgIdAndUserIdOrderByVersionNoDesc(
            UUID fileId, UUID orgId, UUID userId);

    List<FileVersionEntity> findAllById(Collection<UUID> ids);

    long maxVersionNo(UUID fileId);

    long currentUsageByUser(UUID orgId, UUID userId);

    long currentUsageByOrg(UUID orgId);

    FileVersionEntity save(FileVersionEntity version);
}
