/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.workspace;

import java.util.List;
import java.util.UUID;

/** Explicit immutable file bindings, not a scan of the employee's complete workspace. */
public interface WorkspaceInputRepository {
    record Input(
            UUID attachmentId,
            UUID fileId,
            UUID versionId,
            String logicalPath,
            String backend,
            String objectKey,
            Long sizeBytes,
            String sha256) {}

    List<Input> bindings(WorkspaceManifestRepository.Scope scope, int limit);
}
