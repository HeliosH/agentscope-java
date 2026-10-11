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

    /** Authorized caller owns and closes the stream; byte budget is enforced while reading. */
    default java.io.InputStream openRead(UUID orgId, String key, long maxBytes) throws Exception {
        if (maxBytes < 0 || maxBytes > 33554432)
            throw new IllegalArgumentException("Development backend requires bounded small files");
        return new java.io.ByteArrayInputStream(getBounded(orgId, key, Math.max(1, maxBytes)));
    }

    /** Development byte-array backend has an explicit capability ceiling. */
    default void putStream(
            UUID orgId,
            String key,
            java.io.InputStream input,
            long size,
            String type,
            String sha256)
            throws Exception {
        if (size < 0 || size > 33554432)
            throw new IllegalArgumentException(
                    "Development backend requires streaming for large files");
        byte[] bytes = input.readNBytes(Math.toIntExact(size + 1));
        if (bytes.length != size) throw new java.io.IOException("Object transfer length mismatch");
        put(new FileObject(orgId, key, bytes, type, sha256));
    }

    /** Streaming snapshot transfer. The small development fallback is deliberately bounded. */
    default void putFromPath(
            UUID orgId, String key, java.nio.file.Path path, String type, String sha256)
            throws Exception {
        if (java.nio.file.Files.size(path) > 33554432)
            throw new IllegalStateException(
                    "Object backend requires streaming for large snapshots");
        put(new FileObject(orgId, key, java.nio.file.Files.readAllBytes(path), type, sha256));
    }

    /** Copies into a caller-owned private temporary file after metadata authorization. */
    default void copyToPath(UUID orgId, String key, java.nio.file.Path path, long maxBytes)
            throws Exception {
        if (maxBytes < 1 || maxBytes > 33554432)
            throw new IllegalArgumentException("Invalid development snapshot read budget");
        java.nio.file.Files.write(path, getBounded(orgId, key, maxBytes));
    }

    /** Reads an immutable file object after the caller has already passed metadata authorization. */
    byte[] get(UUID orgId, String objectKey) throws Exception;

    /** Reads at most the caller's authorized byte budget; production adapters bound allocation. */
    default byte[] getBounded(UUID orgId, String objectKey, long maxBytes) throws Exception {
        byte[] bytes = get(orgId, objectKey);
        if (bytes == null || bytes.length > maxBytes)
            throw new IllegalStateException("Object exceeds read budget");
        return bytes;
    }

    /** Deletes an unreferenced immutable object. Implementations must be idempotent. */
    void delete(UUID orgId, String objectKey) throws Exception;

    /**
     * Checks whether the backend is reachable enough to accept durable file writes. Implementations
     * keep this lightweight and side-effect free where possible; MinIO/S3 checks bucket reachability,
     * while the PG fallback checks database connectivity.
     */
    default void healthCheck() throws Exception {}
}
