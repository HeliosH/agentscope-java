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
package io.agentscope.extensions.sandbox.cube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP client for the Cube sandbox platform API. Cube exposes an E2B-compatible REST API for
 * sandbox lifecycle management (create, connect, kill).
 */
final class CubePlatformHttp {

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final int maxRetries;

    CubePlatformHttp(OkHttpClient http, ObjectMapper objectMapper, CubeSandboxClientOptions opt) {
        this.http = Objects.requireNonNull(http, "http");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.baseUrl = stripTrailingSlash(opt.getApiUrl());
        this.apiKey = opt.getApiKey();
        this.maxRetries = Math.max(1, opt.getMaxRetries());
    }

    /**
     * Creates a new sandbox via {@code POST /sandboxes}.
     *
     * @param templateId the Cube template to use
     * @param timeout    sandbox idle timeout in seconds
     * @return JSON response containing sandboxId, domain, envdAccessToken, envdVersion
     */
    JsonNode createSandbox(String templateId, int timeout) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("templateID", templateId != null ? templateId : "base");
        body.put("timeout", timeout);
        return CubeRetry.withRetries(maxRetries, () -> post("/sandboxes", body));
    }

    /**
     * Connects to an existing sandbox via {@code POST /sandboxes/{id}/connect}.
     *
     * @param sandboxId the sandbox to connect to
     * @param timeout   connection timeout in seconds
     * @return JSON response (same shape as create)
     */
    JsonNode connectSandbox(String sandboxId, int timeout) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("timeout", timeout);
        return CubeRetry.withRetries(
                maxRetries, () -> post("/sandboxes/" + sandboxId + "/connect", body));
    }

    /**
     * Kills a sandbox via {@code DELETE /sandboxes/{id}}. Tolerates 404 (already gone).
     *
     * @param sandboxId the sandbox to kill
     */
    void killSandbox(String sandboxId) throws Exception {
        CubeRetry.withRetries(
                maxRetries,
                () -> {
                    Request.Builder rb =
                            new Request.Builder().url(baseUrl + "/sandboxes/" + sandboxId).delete();
                    if (apiKey != null && !apiKey.isBlank()) {
                        rb.header("X-API-Key", apiKey);
                    }
                    try (Response res = http.newCall(rb.build()).execute()) {
                        if (!res.isSuccessful() && res.code() != 404) {
                            throw new SandboxException.SandboxRuntimeException(
                                    SandboxErrorCode.WORKSPACE_START_ERROR,
                                    "Cube delete failed: HTTP " + res.code() + " " + res.message());
                        }
                    }
                    return null;
                });
    }

    // ---- internals ----

    private JsonNode post(String path, ObjectNode body) throws IOException, SandboxException {
        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        Request req =
                new Request.Builder()
                        .url(baseUrl + path)
                        .post(RequestBody.create(bodyBytes, JSON_MT))
                        .header("X-API-Key", apiKey)
                        .build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String errBody = res.body() != null ? res.body().string() : "";
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "Cube API " + path + " failed HTTP " + res.code() + ": " + errBody);
            }
            String respBody = res.body() != null ? res.body().string() : "{}";
            return objectMapper.readTree(respBody);
        }
    }

    /**
     * Extracts sandbox fields from a create/connect JSON response and applies them to the state.
     */
    static void applySandboxFields(CubeSandboxState state, JsonNode json) {
        if (json.has("sandboxID")) {
            state.setSandboxId(json.get("sandboxID").asText());
        }
        if (json.has("domain")) {
            state.setSandboxDomain(json.get("domain").asText());
        }
        if (json.has("envdAccessToken")) {
            state.setEnvdAccessToken(json.get("envdAccessToken").asText());
        }
        if (json.has("envdVersion")) {
            state.setEnvdVersion(json.get("envdVersion").asText());
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
