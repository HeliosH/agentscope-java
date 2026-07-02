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
package io.agentscope.extensions.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class OpenSandboxPlatformHttpTest {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createSandboxSendsImageEntrypointAndResourceLimits() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OpenSandboxPlatformHttp http =
                new OpenSandboxPlatformHttp(
                        options(
                                chain -> {
                                    body.set(requestBody(chain));
                                    return jsonResponse(
                                            chain,
                                            202,
                                            """
                                            {"id":"os-1","status":{"state":"Pending"},"createdAt":"2026-01-01T00:00:00Z"}
                                            """);
                                }));

        JsonNode created = http.createSandbox("session-1");

        assertEquals("os-1", created.path("id").asText());
        JsonNode request = MAPPER.readTree(body.get());
        assertEquals("ubuntu:latest", request.path("image").path("uri").asText());
        assertEquals("tail", request.path("entrypoint").get(0).asText());
        assertEquals("1", request.path("resourceLimits").path("cpu").asText());
        assertEquals("1Gi", request.path("resourceLimits").path("memory").asText());
        assertEquals("session-1", request.path("metadata").path("agentscope.session_id").asText());
    }

    @Test
    void runCommandParsesRawJsonSseFrames() throws Exception {
        OpenSandboxPlatformHttp http =
                new OpenSandboxPlatformHttp(
                        options(
                                chain ->
                                        textResponse(
                                                chain,
                                                200,
                                                """
                                                {"type":"stdout","text":"hello\\n"}

                                                {"type":"stderr","text":"warn\\n"}

                                                {"type":"execution_complete","execution_time":1}

                                                """)));

        ExecResult result = http.runCommand(state(), "/workspace", "echo hello", 5);

        assertTrue(result.ok());
        assertEquals("hello\n", result.stdout());
        assertEquals("warn\n", result.stderr());
    }

    @Test
    void runCommandConvertsErrorEventToExecException() {
        OpenSandboxPlatformHttp http =
                new OpenSandboxPlatformHttp(
                        options(
                                chain ->
                                        textResponse(
                                                chain,
                                                200,
                                                """
                                                {"type":"stdout","text":"before\\n"}

                                                {"type":"error","error":{"ename":"CommandExecError","evalue":"7","traceback":["exit status 7"]}}

                                                """)));

        SandboxException.ExecException ex =
                assertThrows(
                        SandboxException.ExecException.class,
                        () -> http.runCommand(state(), "/workspace", "exit 7", 5));

        assertEquals(7, ex.getExitCode());
        assertEquals("before\n", ex.getStdout());
        assertTrue(ex.getStderr().contains("exit status 7"));
    }

    private static OpenSandboxClientOptions options(Interceptor interceptor) {
        OpenSandboxClientOptions options = new OpenSandboxClientOptions();
        options.setHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
        return options;
    }

    private static OpenSandboxState state() {
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId("sandbox-id");
        state.setSessionId("session-id");
        state.setWorkspaceRoot("/workspace");
        return state;
    }

    private static String requestBody(Interceptor.Chain chain) throws IOException {
        Buffer buffer = new Buffer();
        assertNotNull(chain.request().body());
        chain.request().body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private static Response jsonResponse(Interceptor.Chain chain, int code, String body) {
        return response(chain, code, body, JSON);
    }

    private static Response textResponse(Interceptor.Chain chain, int code, String body) {
        return response(chain, code, body, MediaType.get("text/event-stream"));
    }

    private static Response response(
            Interceptor.Chain chain, int code, String body, MediaType mediaType) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, mediaType))
                .build();
    }
}
