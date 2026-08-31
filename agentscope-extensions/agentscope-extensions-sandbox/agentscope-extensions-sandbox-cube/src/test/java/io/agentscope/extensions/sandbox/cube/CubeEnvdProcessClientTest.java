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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class CubeEnvdProcessClientTest {

    @Test
    void proxyUrl_connectsToProxyAndPreservesSandboxRoutingHost() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    captured.set(chain.request());
                                    return new Response.Builder()
                                            .request(chain.request())
                                            .protocol(Protocol.HTTP_1_1)
                                            .code(500)
                                            .message("expected test failure")
                                            .body(
                                                    ResponseBody.create(
                                                            "test", MediaType.get("text/plain")))
                                            .build();
                                })
                        .build();
        CubeSandboxClientOptions options = new CubeSandboxClientOptions();
        options.setHttpClient(http);
        options.setProxyUrl("http://192.168.48.248/");
        CubeSandboxState state = new CubeSandboxState();
        state.setSandboxId("sandbox-123");
        state.setSandboxDomain("tmp.cubsandbox.cc");

        assertThrows(
                RuntimeException.class,
                () -> new CubeEnvdProcessClient(options).runShell(state, "/", "true", 10));

        assertEquals(
                "http://192.168.48.248/process.Process/Start", captured.get().url().toString());
        assertEquals("49983-sandbox-123.tmp.cubsandbox.cc", captured.get().header("Host"));
    }
}
