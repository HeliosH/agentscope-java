/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.FileAttachmentEntity;
import java.util.List;
import java.util.UUID;

/** Domain persistence port for file-to-workflow attachments. */
public interface FileAttachmentRepository {

    List<FileAttachmentEntity> findByOrgIdAndUserIdAndSessionIdOrderByCreatedAtDesc(
            UUID orgId, UUID userId, UUID sessionId);

    List<FileAttachmentEntity> findByOrgIdAndUserIdAndMessageIdOrderByCreatedAtDesc(
            UUID orgId, UUID userId, UUID messageId);

    FileAttachmentEntity save(FileAttachmentEntity attachment);
}
