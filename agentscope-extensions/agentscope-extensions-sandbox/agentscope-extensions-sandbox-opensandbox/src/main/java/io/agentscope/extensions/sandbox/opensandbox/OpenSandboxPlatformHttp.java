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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTP client for OpenSandbox lifecycle and execd-proxy APIs. */
final class OpenSandboxPlatformHttp {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int OUTPUT_TRUNCATE_CHARS = 512 * 1024;
    private static final String API_KEY_HEADER = "OPEN-SANDBOX-API-KEY";
    private static final String EXECD_ACCESS_TOKEN_HEADER = "X-EXECD-ACCESS-TOKEN";

    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final OpenSandboxClientOptions opt;

    OpenSandboxPlatformHttp(OpenSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        if (opt.getHttpClient() != null) {
            this.http = opt.getHttpClient();
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
    }

    JsonNode createSandbox(String sessionId) throws IOException {
        ObjectNode body = json.createObjectNode();
        ObjectNode image = body.putObject("image");
        image.put("uri", required(opt.getImage(), "OpenSandbox image is required"));
        body.put("timeout", Math.max(60, opt.getSandboxTimeoutSeconds()));

        ObjectNode limits = body.putObject("resourceLimits");
        limits.put("cpu", defaultIfBlank(opt.getCpuLimit(), "1"));
        limits.put("memory", defaultIfBlank(opt.getMemoryLimit(), "1Gi"));

        body.putArray("entrypoint").add("tail").add("-f").add("/dev/null");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("agentscope.session_id", sessionId);

        return withRetries(() -> postJson(url("/sandboxes"), body));
    }

    JsonNode getSandbox(String sandboxId) throws IOException {
        return withRetries(() -> getJson(url("/sandboxes/" + pathPart(sandboxId))));
    }

    void deleteSandbox(String sandboxId) throws IOException {
        Request req = requestBuilder(url("/sandboxes/" + pathPart(sandboxId))).delete().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 404) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_STOP_ERROR,
                        "OpenSandbox delete failed: HTTP " + res.code());
            }
        }
    }

    void waitUntilRunning(String sandboxId) throws Exception {
        long deadlineNanos =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(Math.max(1, opt.getWaitTimeoutSeconds()));
        JsonNode last = null;
        while (System.nanoTime() < deadlineNanos) {
            last = getSandbox(sandboxId);
            String state = state(last);
            if ("running".equalsIgnoreCase(state)) {
                return;
            }
            if (isTerminal(state)) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "OpenSandbox entered terminal state: " + state + " " + statusMessage(last));
            }
            Thread.sleep(1000);
        }
        throw new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.WORKSPACE_START_ERROR,
                "Timed out waiting for OpenSandbox to run: "
                        + sandboxId
                        + " lastStatus="
                        + (last == null ? "<none>" : last.path("status").toString()));
    }

    ExecResult runCommand(OpenSandboxState state, String cwd, String command, int timeoutSeconds)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            body.put("cwd", cwd);
        }
        body.put("background", false);
        if (timeoutSeconds > 0) {
            body.put("timeout", TimeUnit.SECONDS.toMillis(timeoutSeconds));
        }

        String commandUrl =
                url(
                        "/sandboxes/"
                                + pathPart(state.getSandboxId())
                                + "/proxy/"
                                + opt.getExecdPort()
                                + "/command");
        Request req =
                requestBuilder(commandUrl).post(RequestBody.create(body.toString(), JSON)).build();
        OkHttpClient callClient =
                timeoutSeconds > 0
                        ? http.newBuilder()
                                .callTimeout(timeoutSeconds + 10L, TimeUnit.SECONDS)
                                .build()
                        : http;
        try (Response res = callClient.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.EXEC_NONZERO,
                        "OpenSandbox execd command failed HTTP " + res.code() + ": " + text);
            }
            return parseCommandEvents(text);
        } catch (InterruptedIOException e) {
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }
    }

    void applySandboxFields(OpenSandboxState state, JsonNode node) {
        if (node != null && node.hasNonNull("id")) {
            state.setSandboxId(node.get("id").asText());
        }
    }

    static String state(JsonNode node) {
        if (node == null) {
            return "";
        }
        return node.path("status").path("state").asText("");
    }

    private ExecResult parseCommandEvents(String stream) throws IOException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean truncated = false;
        boolean complete = false;
        Integer errorExitCode = null;
        String errorText = null;

        try (BufferedReader reader = new BufferedReader(new StringReader(stream))) {
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    JsonNode event = parseFrame(frame.toString());
                    if (event != null) {
                        EventResult result = applyEvent(event, stdout, stderr);
                        truncated |= result.truncated();
                        complete |= result.complete();
                        if (result.errorExitCode() != null) {
                            errorExitCode = result.errorExitCode();
                            errorText = result.errorText();
                        }
                    }
                    frame.setLength(0);
                    continue;
                }
                if (frame.length() > 0) {
                    frame.append('\n');
                }
                frame.append(line);
            }
            JsonNode event = parseFrame(frame.toString());
            if (event != null) {
                EventResult result = applyEvent(event, stdout, stderr);
                truncated |= result.truncated();
                complete |= result.complete();
                if (result.errorExitCode() != null) {
                    errorExitCode = result.errorExitCode();
                    errorText = result.errorText();
                }
            }
        }

        String out = stdout.toString();
        String err = stderr.toString();
        if (errorExitCode != null) {
            if (err.isBlank() && errorText != null) {
                err = errorText;
            }
            throw new SandboxException.ExecException(errorExitCode, out, err);
        }
        if (!complete) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.EXEC_NONZERO,
                    "OpenSandbox execd stream ended without execution_complete event");
        }
        return new ExecResult(0, out, err, truncated);
    }

    private EventResult applyEvent(JsonNode event, StringBuilder stdout, StringBuilder stderr) {
        String type = event.path("type").asText("");
        return switch (type) {
            case "stdout" ->
                    new EventResult(
                            appendLimited(stdout, event.path("text").asText("")),
                            false,
                            null,
                            null);
            case "stderr" ->
                    new EventResult(
                            appendLimited(stderr, event.path("text").asText("")),
                            false,
                            null,
                            null);
            case "execution_complete" -> new EventResult(false, true, null, null);
            case "error" -> {
                JsonNode err = event.path("error");
                String evalue = err.path("evalue").asText("");
                int exitCode = parseExitCode(evalue);
                String text = errorText(err, evalue);
                yield new EventResult(false, false, exitCode, text);
            }
            default -> new EventResult(false, false, null, null);
        };
    }

    private JsonNode parseFrame(String frame) throws IOException {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        String payload = frame.trim();
        if (payload.startsWith("data:")) {
            StringBuilder data = new StringBuilder();
            for (String line : payload.split("\\R")) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            payload = data.toString();
        }
        if (payload.isBlank() || !payload.startsWith("{")) {
            return null;
        }
        return json.readTree(payload);
    }

    private Request.Builder requestBuilder(String url) {
        Request.Builder rb = new Request.Builder().url(url);
        if (opt.getApiKey() != null && !opt.getApiKey().isBlank()) {
            rb.addHeader(API_KEY_HEADER, opt.getApiKey());
        }
        if (opt.getExecdAccessToken() != null && !opt.getExecdAccessToken().isBlank()) {
            rb.addHeader(EXECD_ACCESS_TOKEN_HEADER, opt.getExecdAccessToken());
        }
        return rb;
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        Request req = requestBuilder(url).post(RequestBody.create(body.toString(), JSON)).build();
        try (Response res = http.newCall(req).execute()) {
            return parseJsonResponse(res, "OpenSandbox POST " + url);
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request req = requestBuilder(url).get().build();
        try (Response res = http.newCall(req).execute()) {
            return parseJsonResponse(res, "OpenSandbox GET " + url);
        }
    }

    private JsonNode parseJsonResponse(Response res, String op) throws IOException {
        String text = res.body() != null ? res.body().string() : "";
        if (!res.isSuccessful()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    op + " failed HTTP " + res.code() + ": " + text);
        }
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    private JsonNode withRetries(IoOperation op) throws IOException {
        IOException last = null;
        int attempts = Math.max(1, opt.getMaxRetries());
        for (int i = 0; i < attempts; i++) {
            try {
                return op.run();
            } catch (IOException e) {
                last = e;
                if (i + 1 >= attempts) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(2000L, 200L * (i + 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private String url(String path) {
        return trimSlash(opt.getApiBaseUrl()) + path;
    }

    private static String trimSlash(String u) {
        String value = u == null || u.isBlank() ? OpenSandboxClientOptions.DEFAULT_API_BASE_URL : u;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String pathPart(String value) {
        if (value == null || value.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "OpenSandbox sandbox id is required");
        }
        return value.trim();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(message);
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isTerminal(String state) {
        return "failed".equalsIgnoreCase(state) || "terminated".equalsIgnoreCase(state);
    }

    private static String statusMessage(JsonNode node) {
        return node == null ? "" : node.path("status").path("message").asText("");
    }

    private static int parseExitCode(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String errorText(JsonNode err, String evalue) {
        StringBuilder out = new StringBuilder();
        String ename = err.path("ename").asText("");
        if (!ename.isBlank()) {
            out.append(ename);
        }
        if (evalue != null && !evalue.isBlank()) {
            if (out.length() > 0) {
                out.append(": ");
            }
            out.append(evalue);
        }
        JsonNode traceback = err.path("traceback");
        if (traceback.isArray()) {
            for (JsonNode line : traceback) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line.asText());
            }
        }
        return out.toString();
    }

    private static boolean appendLimited(StringBuilder target, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int remaining = OUTPUT_TRUNCATE_CHARS - target.length();
        if (remaining <= 0) {
            return true;
        }
        if (text.length() <= remaining) {
            target.append(text);
            return false;
        }
        target.append(text, 0, remaining);
        return true;
    }

    private record EventResult(
            boolean truncated, boolean complete, Integer errorExitCode, String errorText) {}

    @FunctionalInterface
    private interface IoOperation {
        JsonNode run() throws IOException;
    }
}
