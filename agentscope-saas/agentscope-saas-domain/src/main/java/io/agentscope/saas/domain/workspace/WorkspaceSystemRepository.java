/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.domain.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable Run-scoped system resources; bytes remain private object-store publications. */
public interface WorkspaceSystemRepository {
    record Manifest(String version, int fileCount, long totalBytes) {}

    record Entry(
            String path,
            UUID publicationId,
            String backend,
            String objectKey,
            long sizeBytes,
            String sha256) {}

    Optional<Manifest> find(WorkspaceManifestRepository.Scope scope);

    List<Entry> entries(WorkspaceManifestRepository.Scope scope, int limit);

    long occupied(UUID orgId, OffsetDateTime now);

    void freeze(WorkspaceManifestRepository.Scope scope, Manifest manifest, List<Entry> entries);
}
