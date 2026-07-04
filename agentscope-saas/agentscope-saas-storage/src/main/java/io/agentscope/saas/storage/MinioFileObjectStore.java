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

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
