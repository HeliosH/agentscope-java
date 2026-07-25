/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Produces a database-independent canonical representation of a permission snapshot. */
public final class PermissionSnapshotIntegrity {

    private static final ObjectMapper CANONICAL_MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private PermissionSnapshotIntegrity() {}

    public static Snapshot canonicalize(String json) {
        String source = json == null || json.isBlank() ? "{}" : json;
        try {
            Object value = CANONICAL_MAPPER.readValue(source, Object.class);
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Permission snapshot must be a JSON object");
            }
            String canonicalJson = CANONICAL_MAPPER.writeValueAsString(value);
            return new Snapshot(canonicalJson, sha256(canonicalJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Permission snapshot is not valid JSON", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Snapshot(String json, String hash) {}
}
