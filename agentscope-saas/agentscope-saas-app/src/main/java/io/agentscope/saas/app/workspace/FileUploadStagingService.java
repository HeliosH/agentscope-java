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

import io.agentscope.saas.app.config.SaasProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Stages multipart uploads on disk with a hard byte limit before scanning and materialization. */
@Component
public class FileUploadStagingService {

    private static final OpenOption[] WRITE_OPTIONS =
            new OpenOption[] {
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            };

    private final SaasProperties properties;
    private final FileContentScanner scanner;

    public FileUploadStagingService(SaasProperties properties, FileContentScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
    }

    public Mono<StagedUpload> stage(FilePart file) {
        SaasProperties.FileStore cfg = properties.getFileStore();
        String filename = safeFilename(file.filename());
        String contentType = declaredContentType(file);
        validateDeclaration(filename, contentType, cfg);
        long maxBytes = Math.max(1L, cfg.getMaxFileBytes());

        return Mono.fromCallable(() -> Files.createTempFile("agentscope-upload-", ".part"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        path -> {
                            AtomicLong size = new AtomicLong();
                            Flux<DataBuffer> bounded =
                                    file.content()
                                            .handle(
                                                    (buffer, sink) -> {
                                                        long total =
                                                                size.addAndGet(
                                                                        buffer.readableByteCount());
                                                        if (total > maxBytes) {
                                                            DataBufferUtils.release(buffer);
                                                            sink.error(
                                                                    new ResponseStatusException(
                                                                            HttpStatus
                                                                                    .PAYLOAD_TOO_LARGE,
                                                                            "Upload exceeds "
                                                                                    + maxBytes
                                                                                    + " bytes"));
                                                        } else {
                                                            sink.next(buffer);
                                                        }
                                                    });
                            return DataBufferUtils.write(bounded, path, WRITE_OPTIONS)
                                    .then(
                                            Mono.fromCallable(
                                                            () -> {
                                                                validateMagic(path);
                                                                scanner.scan(path);
                                                                return new StagedUpload(
                                                                        path,
                                                                        filename,
                                                                        contentType,
                                                                        size.get());
                                                            })
                                                    .subscribeOn(Schedulers.boundedElastic()))
                                    .doOnError(ignored -> deleteQuietly(path))
                                    .doOnCancel(() -> deleteQuietly(path));
                        });
    }

    private static void validateDeclaration(
            String filename, String contentType, SaasProperties.FileStore cfg) {
        Set<String> blockedExtensions =
                cfg.getBlockedExtensions().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        int dot = filename.lastIndexOf('.');
        if (dot >= 0
                && dot < filename.length() - 1
                && blockedExtensions.contains(
                        filename.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Executable file type is not allowed");
        }
        boolean blockedType =
                cfg.getBlockedContentTypes().stream()
                        .filter(value -> value != null)
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(contentType.toLowerCase(Locale.ROOT)::startsWith);
        if (blockedType) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Declared file type is not allowed");
        }
    }

    private static void validateMagic(Path path) throws IOException {
        byte[] header;
        try (var in = Files.newInputStream(path)) {
            header = in.readNBytes(4);
        }
        if (isExecutableHeader(header)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Executable binary upload is not allowed");
        }
    }

    static boolean isExecutableHeader(byte[] header) {
        if (header == null || header.length < 2) {
            return false;
        }
        if (header[0] == 'M' && header[1] == 'Z') {
            return true;
        }
        if (header.length < 4) {
            return false;
        }
        String hex = HexFormat.of().formatHex(header);
        return "7f454c46".equals(hex)
                || "feedface".equals(hex)
                || "feedfacf".equals(hex)
                || "cefaedfe".equals(hex)
                || "cffaedfe".equals(hex)
                || "cafebabe".equals(hex);
    }

    private static String declaredContentType(FilePart file) {
        MediaType type = file.headers().getContentType();
        String value = type != null ? type.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return value.length() <= 255 ? value : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replace('\0', '_').trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            return "upload.bin";
        }
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort for request-scoped staging files.
        }
    }

    public record StagedUpload(Path path, String filename, String contentType, long sizeBytes)
            implements AutoCloseable {

        public byte[] readAllBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}
