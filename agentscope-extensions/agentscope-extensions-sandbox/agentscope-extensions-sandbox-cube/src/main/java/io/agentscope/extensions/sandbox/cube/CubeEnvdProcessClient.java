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

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CubeEnvdProcessClient {

    private static final Logger log = LoggerFactory.getLogger(CubeEnvdProcessClient.class);

    private static final MediaType CONNECT_PROTO = MediaType.get("application/connect+proto");
    private static final int ENVD_PORT = 49983;
    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;

    private final OkHttpClient http;
    private final Descriptors.FileDescriptor fileDescriptor;
    private final Descriptors.Descriptor startRequestDesc;
    private final Descriptors.Descriptor startResponseDesc;
    private final Descriptors.Descriptor processEventDesc;
    private final CubeSandboxClientOptions opt;

    CubeEnvdProcessClient(CubeSandboxClientOptions opt) throws Exception {
        this.opt = Objects.requireNonNull(opt, "opt");
        this.http = CubeHttpClients.create(opt);
        try (InputStream in =
                CubeEnvdProcessClient.class.getResourceAsStream("/e2b-process-fdp.pb")) {
            if (in == null) {
                throw new IOException("Missing classpath resource /e2b-process-fdp.pb");
            }
            com.google.protobuf.DescriptorProtos.FileDescriptorProto fdp =
                    com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(
                            in.readAllBytes());
            this.fileDescriptor =
                    com.google.protobuf.Descriptors.FileDescriptor.buildFrom(
                            fdp, new com.google.protobuf.Descriptors.FileDescriptor[0]);
        }
        this.startRequestDesc = fileDescriptor.findMessageTypeByName("StartRequest");
        this.startResponseDesc = fileDescriptor.findMessageTypeByName("StartResponse");
        this.processEventDesc = fileDescriptor.findMessageTypeByName("ProcessEvent");
    }

    ExecResult runShell(CubeSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        ShellCapture cap = runShellCapture(state, cwd, shellCommand, timeoutSeconds);
        String outStr = truncateUtf8(cap.stdout.toByteArray());
        String errStr = truncateUtf8(cap.stderr.toByteArray());
        boolean truncated =
                cap.stdout.size() >= OUTPUT_TRUNCATE_BYTES
                        || cap.stderr.size() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult r = new ExecResult(cap.exitCode, outStr, errStr, truncated);
        if (!r.ok()) {
            throw new SandboxException.ExecException(cap.exitCode, outStr, errStr);
        }
        return r;
    }

    byte[] runShellBinaryStdout(
            CubeSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        ShellCapture cap = runShellCapture(state, cwd, shellCommand, timeoutSeconds);
        if (cap.exitCode != 0) {
            throw new SandboxException.ExecException(
                    cap.exitCode, "(binary stdout)", truncateUtf8(cap.stderr.toByteArray()));
        }
        return cap.stdout.toByteArray();
    }

    private ShellCapture runShellCapture(
            CubeSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        OkHttpClient callClient =
                timeoutSeconds > 0
                        ? http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
                        : http;
        String host = envdHost(state);
        String url = host + "/process.Process/Start";
        DynamicMessage startReq = buildStartRequest(shellCommand, cwd);
        byte[] envelope = encodeUnaryEnvelope(startReq.toByteArray());
        Request.Builder rb =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(envelope, CONNECT_PROTO))
                        .addHeader("Connect-Protocol-Version", "1")
                        .addHeader("User-Agent", "agentscope-java-cube")
                        .addHeader("E2b-Sandbox-Id", state.getSandboxId())
                        .addHeader("E2b-Sandbox-Port", Integer.toString(ENVD_PORT))
                        .addHeader("Authorization", basicAuthUser(opt.getRunUser()));
        if (state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank()) {
            rb.addHeader("X-Access-Token", state.getEnvdAccessToken());
        }
        Request req = rb.build();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = -1;
        try (Response res = callClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String err = res.body() != null ? res.body().string() : "";
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "envd Start failed HTTP " + res.code() + ": " + err);
            }
            try (InputStream in = res.body().byteStream()) {
                exit = drainStartStream(in, stdout, stderr);
            }
        } catch (Exception e) {
            String message =
                    "envd request failed host="
                            + host
                            + " sandboxId="
                            + state.getSandboxId()
                            + " cmd="
                            + shellCommand
                            + ": "
                            + e.getMessage()
                            + ". "
                            + diagnosticHint(state, host, e);
            log.warn(
                    "[cube-envd] exec failed cmd={} host={} exit={} stdout='{}' stderr='{}'",
                    shellCommand,
                    host,
                    exit,
                    new String(stdout.toByteArray(), StandardCharsets.UTF_8).trim(),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim(),
                    e);
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR, message, e);
        }
        log.debug(
                "[cube-envd] exit={} stdout='{}' stderr='{}'",
                exit,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8).trim(),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim());
        return new ShellCapture(exit, stdout, stderr);
    }

    private record ShellCapture(
            int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {}

    private int drainStartStream(
            InputStream in, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws IOException {
        int exit = 0; // default success; overridden by end.exit_code if present
        Descriptors.FieldDescriptor srEventF = startResponseDesc.findFieldByName("event");
        Descriptors.FieldDescriptor peDataF = processEventDesc.findFieldByName("data");
        Descriptors.FieldDescriptor peEndF = processEventDesc.findFieldByName("end");
        while (true) {
            int flags = in.read();
            if (flags == -1) {
                break;
            }
            byte[] lenB = in.readNBytes(4);
            if (lenB.length < 4) {
                break;
            }
            int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt() & 0x7FFFFFFF;
            if (len < 0 || len > 64 * 1024 * 1024) {
                throw new IOException("Invalid connect frame length: " + len);
            }
            byte[] data = in.readNBytes(len);
            if (data.length < len) {
                break;
            }
            if (flags != 0x00 && flags != 0x02) {
                continue;
            }
            DynamicMessage sr;
            try {
                sr = DynamicMessage.parseFrom(startResponseDesc, data);
            } catch (InvalidProtocolBufferException e) {
                continue;
            }
            if (!sr.hasField(srEventF)) {
                continue;
            }
            DynamicMessage pe = (DynamicMessage) sr.getField(srEventF);
            if (pe.hasField(peDataF)) {
                DynamicMessage de = (DynamicMessage) pe.getField(peDataF);
                appendDataStream(de, "stdout", stdout);
                appendDataStream(de, "stderr", stderr);
            }
            if (pe.hasField(peEndF)) {
                DynamicMessage end = (DynamicMessage) pe.getField(peEndF);
                Descriptors.FieldDescriptor ec =
                        end.getDescriptorForType().findFieldByName("exit_code");
                if (end.hasField(ec)) {
                    Object v = end.getField(ec);
                    exit = v instanceof Integer ? (Integer) v : ((Long) v).intValue();
                }
            }
        }
        return exit;
    }

    private static void appendDataStream(
            DynamicMessage dataEv, String field, ByteArrayOutputStream out) throws IOException {
        Descriptors.FieldDescriptor f = dataEv.getDescriptorForType().findFieldByName(field);
        if (f == null || !dataEv.hasField(f)) {
            return;
        }
        Object v = dataEv.getField(f);
        if (v instanceof ByteString) {
            ((ByteString) v).writeTo(out);
        }
    }

    private DynamicMessage buildStartRequest(String shellCommand, String cwd) {
        Descriptors.Descriptor pcDesc = fileDescriptor.findMessageTypeByName("ProcessConfig");
        DynamicMessage.Builder pcb = DynamicMessage.newBuilder(pcDesc);
        pcb.setField(pcDesc.findFieldByName("cmd"), "/bin/bash");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-l");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-c");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), shellCommand);
        if (cwd != null && !cwd.isBlank()) {
            pcb.setField(pcDesc.findFieldByName("cwd"), cwd);
        }
        DynamicMessage.Builder sb = DynamicMessage.newBuilder(startRequestDesc);
        sb.setField(startRequestDesc.findFieldByName("process"), pcb.build());
        sb.setField(startRequestDesc.findFieldByName("stdin"), false);
        return sb.build();
    }

    private static byte[] encodeUnaryEnvelope(byte[] msg) {
        byte[] out = new byte[5 + msg.length];
        out[0] = 0x00;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(msg.length);
        System.arraycopy(msg, 0, out, 5, msg.length);
        return out;
    }

    /**
     * Constructs the envd host URL using the configurable pattern. The pattern supports
     * placeholders: {@code {port}}, {@code {sandboxId}}, {@code {domain}}.
     */
    private String envdHost(CubeSandboxState state) {
        String domain =
                state.getSandboxDomain() != null && !state.getSandboxDomain().isBlank()
                        ? state.getSandboxDomain()
                        : opt.getDomain();
        String pattern = opt.getEnvdHostPattern();
        String host =
                pattern.replace("{port}", Integer.toString(ENVD_PORT))
                        .replace("{sandboxId}", state.getSandboxId())
                        .replace("{domain}", domain);
        // If the pattern doesn't start with http, assume https
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        return host;
    }

    private String diagnosticHint(CubeSandboxState state, String host, Exception error) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cube envd diagnostics: pattern=")
                .append(opt.getEnvdHostPattern())
                .append(", domain=")
                .append(
                        state.getSandboxDomain() != null && !state.getSandboxDomain().isBlank()
                                ? state.getSandboxDomain()
                                : opt.getDomain())
                .append(", envdPort=")
                .append(ENVD_PORT)
                .append(", insecureSkipTlsVerify=")
                .append(opt.isInsecureSkipTlsVerify())
                .append(". ");

        Throwable cause = rootCause(error);
        if (cause instanceof SSLException) {
            sb.append("TLS failed after the sandbox was created; verify that the envd ingress for ")
                    .append(host)
                    .append(
                            " terminates HTTPS to the envd process service. If this Cube deployment"
                                + " exposes envd over plain HTTP, set"
                                + " SAAS_SANDBOX_CUBE_ENVD_HOST_PATTERN/CUBE_ENVD_HOST_PATTERN to"
                                + " an http:// pattern.");
        } else if (cause instanceof ConnectException) {
            sb.append(
                            "The envd endpoint was not reachable; verify dynamic port"
                                    + " routing/firewall for port ")
                    .append(ENVD_PORT)
                    .append(" and the sandbox domain.");
        } else if (cause instanceof SocketTimeoutException) {
            sb.append(
                    "The envd endpoint timed out; verify the sandbox is ready, ingress routes"
                            + " dynamic envd ports, and SAAS_SANDBOX_CUBE_TIMEOUT is sufficient.");
        } else {
            sb.append(
                    "Check SAAS_SANDBOX_CUBE_ENVD_HOST_PATTERN/CUBE_ENVD_HOST_PATTERN,"
                        + " SAAS_SANDBOX_CUBE_DOMAIN/CUBE_DOMAIN, and dynamic envd port routing.");
        }
        return sb.toString();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String basicAuthUser(String user) {
        String u = user != null ? user : "user";
        String token =
                Base64.getEncoder().encodeToString((u + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String truncateUtf8(byte[] b) {
        int n = Math.min(b.length, OUTPUT_TRUNCATE_BYTES);
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }
}
