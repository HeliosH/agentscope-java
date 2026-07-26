/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical JSON and digest for structured planner payloads. */
final class ExecutionPlanIntegrity {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ExecutionPlanIntegrity() {}

    static CanonicalPayload canonicalize(Object value) {
        try {
            String json = MAPPER.writeValueAsString(value);
            return new CanonicalPayload(json, sha256(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Execution plan cannot be serialized", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    record CanonicalPayload(String json, String hash) {}
}
