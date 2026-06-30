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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class E2bSandboxWorkspaceSetupTest {

    @Test
    void defaultsUseDedicatedWorkspaceDirectoryUnderHome() {
        assertEquals(
                E2bSandboxClientOptions.DEFAULT_WORKSPACE_ROOT,
                new E2bSandboxClientOptions().getWorkspaceRoot());
        assertEquals(
                E2bSandboxClientOptions.DEFAULT_WORKSPACE_ROOT,
                new E2bSandboxState().getWorkspaceRoot());
    }

    @Test
    void setupWorkspaceRunsFromStableRootCwd() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        E2bSandbox sandbox = new ExposedE2bSandbox(state(), options(capturedBody));

        assertThrows(Exception.class, ((ExposedE2bSandbox) sandbox)::setupWorkspace);

        DynamicMessage request = decodeStartRequest(capturedBody.get());
        Descriptors.FieldDescriptor processField =
                request.getDescriptorForType().findFieldByName("process");
        DynamicMessage process = (DynamicMessage) request.getField(processField);

        assertEquals("/", process.getField(process.getDescriptorForType().findFieldByName("cwd")));
    }

    private static E2bSandboxState state() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/home/user");
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(spec);
        state.setWorkspaceRoot("/home/user");
        state.setSandboxId("sandbox-id");
        state.setSandboxDomain("e2b.app");
        return state;
    }

    private static E2bSandboxClientOptions options(AtomicReference<byte[]> capturedBody) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setHttpClient(
                new OkHttpClient.Builder()
                        .addInterceptor(capturingFailureInterceptor(capturedBody))
                        .build());
        return options;
    }

    private static Interceptor capturingFailureInterceptor(AtomicReference<byte[]> capturedBody) {
        return chain -> {
            Buffer buffer = new Buffer();
            okhttp3.RequestBody body = chain.request().body();
            assertNotNull(body);
            body.writeTo(buffer);
            capturedBody.set(buffer.readByteArray());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("envd unavailable")
                    .build();
        };
    }

    private static DynamicMessage decodeStartRequest(byte[] envelope) throws Exception {
        assertNotNull(envelope);
        ByteBuffer lenBuffer = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN);
        int len = lenBuffer.getInt();
        byte[] payload = new byte[len];
        System.arraycopy(envelope, 5, payload, 0, len);
        Descriptors.Descriptor descriptor =
                e2bProcessDescriptor().findMessageTypeByName("StartRequest");
        return DynamicMessage.parseFrom(descriptor, payload);
    }

    private static Descriptors.FileDescriptor e2bProcessDescriptor() throws Exception {
        try (InputStream in =
                E2bEnvdProcessClient.class.getResourceAsStream("/e2b-process-fdp.pb")) {
            assertNotNull(in);
            DescriptorProtos.FileDescriptorProto proto =
                    DescriptorProtos.FileDescriptorProto.parseFrom(in.readAllBytes());
            return Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[0]);
        }
    }

    private static final class ExposedE2bSandbox extends E2bSandbox {
        private ExposedE2bSandbox(E2bSandboxState state, E2bSandboxClientOptions options) {
            super(state, options);
        }

        private void setupWorkspace() throws Exception {
            doSetupWorkspace();
        }
    }
}
