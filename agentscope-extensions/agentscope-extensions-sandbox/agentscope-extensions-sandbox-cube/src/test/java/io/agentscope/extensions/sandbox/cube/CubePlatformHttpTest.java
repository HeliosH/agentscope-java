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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
                        new CubeHostMount("/data/shared/output", "/output", false)));

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
        assertNull(captured.get().header("X-API-Key"));
    }
}
