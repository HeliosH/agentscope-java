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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class CubePlatformHttpTest {

    private static final MediaType JSON = MediaType.get("application/json");

    @Test
    void create_encodesHostMountsInCubeMetadataContract() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    captured.set(chain.request());
                                    return new Response.Builder()
                                            .request(chain.request())
                                            .protocol(Protocol.HTTP_1_1)
                                            .code(200)
                                            .message("OK")
                                            .body(
                                                    ResponseBody.create(
                                                            "{\"sandboxID\":\"sb-1\"}", JSON))
                                            .build();
                                })
                        .build();
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setApiUrl("http://cube.test");
        options.setHttpClient(http);

        CubePlatformHttp platform = new CubePlatformHttp(http, new ObjectMapper(), options);
        platform.createSandbox(
                "tpl-code",
                300,
                List.of(
                        new CubeHostMount("/data/shared/skills", "/opt/skills", true),
                        new CubeHostMount("/data/shared/output", "/output", false)),
                List.of(
                        CubeVolumeMount.managed("agentscope-ws-123", "/home/user", false, "s3"),
                        CubeVolumeMount.existing("enterprise-skills", "/opt/shared", true)));

        Buffer body = new Buffer();
        captured.get().body().writeTo(body);
        JsonNode request = new ObjectMapper().readTree(body.readUtf8());
        JsonNode mounts =
                new ObjectMapper().readTree(request.path("metadata").path("host-mount").asText());
        assertEquals("tpl-code", request.path("templateID").asText());
        assertEquals(2, mounts.size());
        assertEquals("/data/shared/skills", mounts.get(0).path("hostPath").asText());
        assertEquals("/opt/skills", mounts.get(0).path("mountPath").asText());
        assertEquals(true, mounts.get(0).path("readOnly").asBoolean());
        assertEquals(2, request.path("volumeMounts").size());
        assertEquals(
                "agentscope-ws-123", request.path("volumeMounts").get(0).path("name").asText());
        assertEquals("/home/user", request.path("volumeMounts").get(0).path("path").asText());
        assertTrue(request.path("volumeMounts").get(0).path("readOnly").isMissingNode());
        assertTrue(request.path("volumeMounts").get(1).path("readOnly").asBoolean());
        assertNull(captured.get().header("X-API-Key"));
    }

    @Test
    void create_doesNotReplayRequestAfterAmbiguousTransportFailure() {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    requests.incrementAndGet();
                                    throw new IOException("response stream closed");
                                })
                        .build();
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setApiUrl("http://cube.test");
        options.setMaxRetries(3);

        CubePlatformHttp platform = new CubePlatformHttp(http, new ObjectMapper(), options);

        assertThrows(
                IOException.class,
                () -> platform.createSandbox("tpl-code", 300, List.of(), List.of()));
        assertEquals(1, requests.get());
    }

    @Test
    void ensureVolume_createsAfterNotFoundAndReusesDeterministicName() throws Exception {
        AtomicReference<String> methods = new AtomicReference<>("");
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    Request request = chain.request();
                                    methods.updateAndGet(
                                            value ->
                                                    value
                                                            + request.method()
                                                            + " "
                                                            + request.url().encodedPath()
                                                            + "\n");
                                    if ("GET".equals(request.method())) {
                                        return response(request, 404, "{}");
                                    }
                                    return response(
                                            request,
                                            201,
                                            "{\"volumeID\":\"agentscope-ws-1\","
                                                + "\"name\":\"agentscope-ws-1\",\"token\":\"t\"}");
                                })
                        .build();
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setApiUrl("http://cube.test");

        CubeVolumeInfo info =
                new CubePlatformHttp(http, new ObjectMapper(), options)
                        .ensureVolume("agentscope-ws-1", "s3");

        assertEquals("agentscope-ws-1", info.volumeId());
        assertEquals("GET /volumes/agentscope-ws-1\nPOST /volumes\n", methods.get());
    }

    private static Response response(Request request, int code, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 404 ? "Not Found" : "OK")
                .body(ResponseBody.create(body, JSON))
                .build();
    }
}
