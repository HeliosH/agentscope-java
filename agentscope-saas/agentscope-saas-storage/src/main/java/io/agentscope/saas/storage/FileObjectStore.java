/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.storage;

import java.util.UUID;

/** Durable object store for workspace file bytes. Metadata and authorization stay in PostgreSQL. */
public interface FileObjectStore {

    /** Low-cardinality backend name persisted in {@code file_versions.storage_backend}. */
    String backend();

    /** Stores a complete immutable file object. */
    void put(FileObject object) throws Exception;

    /** Reads an immutable file object after the caller has already passed metadata authorization. */
    byte[] get(UUID orgId, String objectKey) throws Exception;

    /**
     * Checks whether the backend is reachable enough to accept durable file writes. Implementations
     * keep this lightweight and side-effect free where possible; MinIO/S3 checks bucket reachability,
     * while the PG fallback checks database connectivity.
     */
    default void healthCheck() throws Exception {}
}
