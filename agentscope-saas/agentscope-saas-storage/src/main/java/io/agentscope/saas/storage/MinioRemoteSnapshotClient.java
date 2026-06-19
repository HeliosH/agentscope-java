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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.util.Objects;

/**
 * {@link RemoteSnapshotClient} backed by an S3-compatible MinIO (or any S3 API) bucket. Stores each
 * workspace tar archive as a single object under {@code <keyPrefix>/<snapshotId>.tar.gz}.
 *
 * <p>Replaces {@link PgRemoteSnapshotClient} for production: object storage scales to large
 * workspaces and high cardinality without bloating the Postgres {@code BYTEA} table, which is kept
 * only as a dev/H2 fallback (no extra infra). The snapshot id is globally unique (a session UUID),
 * so flat keying under the prefix is sufficient; a tenant/org-segmented layout can be layered on
 * later by making the snapshot id carry a tenant prefix.
 *
 * <p>The bucket is created on first use if it does not exist (dev convenience). Authentication uses
 * access/secret keys configured on the {@link MinioClient} passed in.
 */
public final class MinioRemoteSnapshotClient implements RemoteSnapshotClient {

    private final MinioClient client;
    private final String bucket;
    private final String keyPrefix;

    /**
     * Creates a MinIO snapshot client.
     *
     * @param client configured {@link MinioClient} (endpoint, credentials, region)
     * @param bucket bucket name (created if absent)
     * @param keyPrefix object key prefix (e.g. {@code "snapshots/"}); trailing slash normalized
     */
    public MinioRemoteSnapshotClient(MinioClient client, String bucket, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        String prefix = keyPrefix == null ? "" : keyPrefix.trim();
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        this.keyPrefix = prefix;
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        ensureBucket();
        client.putObject(
                PutObjectArgs.builder().bucket(bucket).object(objectKey(snapshotId)).stream(
                                data, -1, 10 * 1024 * 1024)
                        .contentType("application/gzip")
                        .build());
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        return client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey(snapshotId)).build());
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        try {
            client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey(snapshotId)).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse().code();
            // NoSuchKey = object absent; NoSuchBucket = bucket not yet created (first use).
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw e;
        }
    }

    private String objectKey(String snapshotId) {
        return keyPrefix + snapshotId + ".tar.gz";
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
