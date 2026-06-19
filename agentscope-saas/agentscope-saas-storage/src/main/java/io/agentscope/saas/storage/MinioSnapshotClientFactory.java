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

import io.minio.MinioClient;
import java.util.Objects;

/**
 * Factory that constructs a {@link MinioRemoteSnapshotClient} from plain config values, keeping the
 * {@code io.minio} API out of the SaaS app config layer (which would otherwise need a direct MinIO
 * dependency). The MinIO dependency is {@code optional} on the storage module; callers that use this
 * factory pull it in transitively.
 */
public final class MinioSnapshotClientFactory {

    private MinioSnapshotClientFactory() {}

    /**
     * Builds a {@link MinioRemoteSnapshotClient}.
     *
     * @param endpoint MinIO/S3 endpoint URL
     * @param accessKey access key
     * @param secretKey secret key
     * @param region AWS region (nullable/blank for MinIO)
     * @param bucket bucket name (created if absent on first use)
     * @param keyPrefix object key prefix (e.g. {@code "snapshots/"})
     */
    public static MinioRemoteSnapshotClient create(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            String bucket,
            String keyPrefix) {
        Objects.requireNonNull(endpoint, "endpoint");
        MinioClient.Builder b =
                MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            b.region(region);
        }
        return new MinioRemoteSnapshotClient(b.build(), bucket, keyPrefix);
    }
}
