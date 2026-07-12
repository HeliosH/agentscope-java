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
package io.agentscope.saas.app.workspace;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Minimal ClamAV INSTREAM client suitable for an internally deployed clamd service. */
public final class ClamAvFileContentScanner implements FileContentScanner {

    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_RESPONSE_SIZE = 8 * 1024;

    private final String host;
    private final int port;
    private final int timeoutMillis;
    private final boolean failClosed;

    public ClamAvFileContentScanner(String host, int port, int timeoutSeconds, boolean failClosed) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        this.failClosed = failClosed;
    }

    @Override
    public void scan(Path path) throws Exception {
        try {
            String response = scanWithClamd(path);
            String normalized = response.toUpperCase(Locale.ROOT);
            if (normalized.contains(" FOUND")) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Upload rejected by malware scanner");
            }
            if (!normalized.contains(" OK")) {
                throw new IllegalStateException("Unexpected ClamAV response: " + response.trim());
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (failClosed) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "File malware scanner is unavailable", e);
            }
        }
    }

    private String scanWithClamd(Path path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    BufferedInputStream file = new BufferedInputStream(Files.newInputStream(path));
                    ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[CHUNK_SIZE];
                int read;
                while ((read = file.read(chunk)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    out.writeInt(read);
                    out.write(chunk, 0, read);
                }
                out.writeInt(0);
                out.flush();

                int value;
                while ((value = socket.getInputStream().read()) >= 0 && value != 0) {
                    if (response.size() >= MAX_RESPONSE_SIZE) {
                        throw new IllegalStateException("ClamAV response exceeded limit");
                    }
                    response.write(value);
                }
                return response.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
