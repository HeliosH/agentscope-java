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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Manual smoke probe for {@link MinioRemoteSnapshotClient} against a real MinIO instance. Run with
 * env vars {@code MINIO_ENDPOINT}, {@code MINIO_ACCESS_KEY}, {@code MINIO_SECRET_KEY},
 * {@code MINIO_BUCKET} (defaults to localhost:9000 / minioadmin / agentscope-saas).
 *
 * <p>Verifies the round-trip: exists==false → upload → exists==true → download matches → (no
 * delete; bucket reused across runs). Not a unit test — requires a live MinIO.
 */
public class MinioSnapshotProbe {

    public static void main(String[] args) throws Exception {
        String endpoint = env("MINIO_ENDPOINT", "http://localhost:9000");
        String accessKey = env("MINIO_ACCESS_KEY", "minioadmin");
        String secretKey = env("MINIO_SECRET_KEY", "minioadmin");
        String bucket = env("MINIO_BUCKET", "agentscope-saas");
        String keyPrefix = env("MINIO_KEY_PREFIX", "snapshots/");

        MinioRemoteSnapshotClient client =
                MinioSnapshotClientFactory.create(
                        endpoint, accessKey, secretKey, null, bucket, keyPrefix);
        String snapshotId = "probe-" + System.currentTimeMillis();
        byte[] payload = ("hello-minio-" + snapshotId).getBytes(StandardCharsets.UTF_8);

        System.out.println(
                "[probe] endpoint=" + endpoint + " bucket=" + bucket + " id=" + snapshotId);
        System.out.println("[probe] exists(before)=" + client.exists(snapshotId));
        client.upload(snapshotId, new ByteArrayInputStream(payload));
        System.out.println("[probe] exists(after upload)=" + client.exists(snapshotId));
        try (InputStream in = client.download(snapshotId)) {
            byte[] downloaded = in.readAllBytes();
            boolean match =
                    new String(downloaded, StandardCharsets.UTF_8)
                            .equals(new String(payload, StandardCharsets.UTF_8));
            System.out.println("[probe] download match=" + match + " bytes=" + downloaded.length);
            if (!match) {
                System.out.println("[probe] RESULT=FAIL payload mismatch");
                return;
            }
        }
        System.out.println("[probe] RESULT=OK");
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v;
    }
}
