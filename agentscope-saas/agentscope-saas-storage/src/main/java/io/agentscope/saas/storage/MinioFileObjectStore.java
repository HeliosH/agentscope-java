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

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.UUID;

/** MinIO/S3-backed file object store for enterprise workspace file bytes. */
public final class MinioFileObjectStore implements FileObjectStore {

    private final MinioClient client;
    private final String bucket;

    public MinioFileObjectStore(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    @Override
    public String backend() {
        return "minio";
    }

    @Override
    public void put(FileObject object) throws Exception {
        Objects.requireNonNull(object, "object");
        ensureBucket();
        byte[] content = object.content() != null ? object.content() : new byte[0];
        client.putObject(
                PutObjectArgs.builder().bucket(bucket).object(object.objectKey()).stream(
                                new ByteArrayInputStream(content), content.length, -1)
                        .contentType(
                                object.contentType() != null
                                        ? object.contentType()
                                        : "application/octet-stream")
                        .build());
    }

    @Override
    public byte[] get(UUID orgId, String objectKey) throws Exception {
        try (var in =
                client.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        }
    }

    @Override
    public java.io.InputStream openRead(UUID orgId, String key, long maxBytes) throws Exception {
        if (maxBytes < 0) throw new IllegalArgumentException("Invalid object read budget");
        var delegate = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        return new java.io.FilterInputStream(delegate) {
            private long remaining = maxBytes;

            @Override
            public int read() throws java.io.IOException {
                int value = in.read();
                if (value >= 0 && remaining-- == 0)
                    throw new java.io.IOException("Object exceeds read budget");
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                int read =
                        in.read(
                                bytes,
                                offset,
                                (int)
                                        Math.min(
                                                length,
                                                remaining == Long.MAX_VALUE
                                                        ? remaining
                                                        : remaining + 1));
                if (read > remaining) throw new java.io.IOException("Object exceeds read budget");
                if (read > 0) remaining -= read;
                return read;
            }

            @Override
            public long skip(long count) throws java.io.IOException {
                if (count <= 0) return 0;
                byte[] buffer = new byte[8192];
                long skipped = 0;
                while (skipped < count) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                    if (read < 0) break;
                    skipped += read;
                }
                return skipped;
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws java.io.IOException {
                throw new java.io.IOException("Bounded object streams cannot reset");
            }
        };
    }

    @Override
    public void putStream(
            UUID orgId,
            String key,
            java.io.InputStream input,
            long size,
            String type,
            String sha256)
            throws Exception {
        if (size < 0) throw new IllegalArgumentException("Invalid object size");
        ensureBucket();
        client.putObject(
                PutObjectArgs.builder().bucket(bucket).object(key).stream(input, size, -1)
                        .contentType(type == null ? "application/octet-stream" : type)
                        .build());
    }

    @Override
    public void putFromPath(
            UUID orgId, String key, java.nio.file.Path path, String type, String sha256)
            throws Exception {
        ensureBucket();
        try (var in = java.nio.file.Files.newInputStream(path)) {
            putStream(orgId, key, in, java.nio.file.Files.size(path), type, sha256);
        }
    }

    @Override
    public void copyToPath(UUID orgId, String key, java.nio.file.Path path, long maxBytes)
            throws Exception {
        if (maxBytes < 1 || maxBytes > 536870912)
            throw new IllegalArgumentException("Invalid snapshot read budget");
        try (var in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
                var out = java.nio.file.Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total = Math.addExact(total, count);
                if (total > maxBytes)
                    throw new IllegalStateException("Snapshot exceeds read budget");
                out.write(buffer, 0, count);
            }
        }
    }

    @Override
    public byte[] getBounded(UUID orgId, String objectKey, long maxBytes) throws Exception {
        if (maxBytes < 1 || maxBytes >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid object read budget");
        try (var in = openRead(orgId, objectKey, maxBytes)) {
            byte[] bytes = in.readNBytes(Math.toIntExact(maxBytes + 1));
            if (bytes.length > maxBytes)
                throw new IllegalStateException("Object exceeds read budget");
            return bytes;
        }
    }

    @Override
    public void delete(UUID orgId, String objectKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }

    @Override
    public void healthCheck() throws Exception {
        ensureBucket();
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
