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

/** Factory for creating MinIO-backed workspace file object storage from plain config values. */
public final class MinioFileObjectStoreFactory {

    private MinioFileObjectStoreFactory() {}

    public static MinioFileObjectStore create(
            String endpoint, String accessKey, String secretKey, String region, String bucket) {
        Objects.requireNonNull(endpoint, "endpoint");
        MinioClient.Builder b =
                MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            b.region(region);
        }
        return new MinioFileObjectStore(b.build(), bucket);
    }
}
