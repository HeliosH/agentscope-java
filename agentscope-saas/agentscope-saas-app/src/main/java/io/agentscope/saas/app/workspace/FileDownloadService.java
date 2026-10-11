/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Application-authorized downloads with bounded buffers and single-range HTTP support. */
@Service
public class FileDownloadService {
    private final WorkspaceTransferResources resources;

    public FileDownloadService(WorkspaceTransferResources resources) {
        this.resources = resources;
    }

    public String previewText(FileCatalogService.VersionStream file, int limit) throws Exception {
        if (limit < 1 || limit > 512 * 1024 || file.sizeBytes() > limit)
            throw new IllegalArgumentException("File exceeds text preview capacity");
        try (var permit = resources.acquire();
                var out = new java.io.ByteArrayOutputStream()) {
            var content =
                    new io.agentscope.harness.agent.sandbox.WorkspaceRestorePlan.WorkspaceFile(
                            file.logicalPath(), file.sizeBytes(), file.sha256(), file.source());
            content.transferTo(out, 4096);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public ResponseEntity<Flux<DataBuffer>> response(
            FileCatalogService.VersionStream file, String range) {
        long start = 0, end = file.sizeBytes() - 1;
        boolean partial = range != null && !range.isBlank();
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag("\"" + file.sha256() + "\"");
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        String name = file.logicalPath().substring(file.logicalPath().lastIndexOf('/') + 1);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        headers.set("X-Content-Type-Options", "nosniff");
        if (partial) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1 || file.sizeBytes() == 0)
                    throw new IllegalArgumentException();
                start = ranges.get(0).getRangeStart(file.sizeBytes());
                end = ranges.get(0).getRangeEnd(file.sizeBytes());
                if (start < 0 || start >= file.sizeBytes() || end < start)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + file.sizeBytes());
                headers.setContentLength(0);
                return new ResponseEntity<>(
                        Flux.empty(), headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            }
            headers.set(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + file.sizeBytes());
        }
        long count = file.sizeBytes() == 0 ? 0 : end - start + 1;
        headers.setContentLength(count);
        long offset = start;
        Flux<DataBuffer> body =
                count == 0
                        ? Flux.empty()
                        : DataBufferUtils.readInputStream(
                                        () -> open(file, offset, count),
                                        DefaultDataBufferFactory.sharedInstance,
                                        resources.bufferBytes())
                                .subscribeOn(Schedulers.boundedElastic());
        return new ResponseEntity<>(
                body, headers, partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    private InputStream open(FileCatalogService.VersionStream file, long offset, long count)
            throws IOException {
        WorkspaceTransferResources.Permit permit;
        try {
            permit = resources.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", interrupted);
        }
        InputStream input = null;
        try {
            input = file.source().open();
            input.skipNBytes(offset);
            return new FilterInputStream(input) {
                private long remaining = count;
                private boolean closed;

                @Override
                public int read() throws IOException {
                    if (remaining == 0) return -1;
                    int value = in.read();
                    if (value < 0) throw new IOException("File truncated during download");
                    remaining--;
                    return value;
                }

                @Override
                public int read(byte[] bytes, int off, int len) throws IOException {
                    java.util.Objects.checkFromIndexSize(off, len, bytes.length);
                    if (len == 0) return 0;
                    if (remaining == 0) return -1;
                    int read = in.read(bytes, off, (int) Math.min(len, remaining));
                    if (read < 0) throw new IOException("File truncated during download");
                    remaining -= read;
                    return read;
                }

                @Override
                public synchronized void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        try {
                            super.close();
                        } finally {
                            permit.close();
                        }
                    }
                }
            };
        } catch (Exception error) {
            if (input != null)
                try {
                    input.close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            permit.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("Authorized file unavailable", error);
        }
    }
}
