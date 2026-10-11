/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;

class FileDownloadServiceTest {
    private final WorkspaceTransferResources resources =
            new WorkspaceTransferResources(1, 100, 4096);
    private final FileDownloadService service = new FileDownloadService(resources);
    private final AtomicBoolean closed = new AtomicBoolean();

    private FileCatalogService.VersionStream file() {
        return new FileCatalogService.VersionStream(
                "outputs/report.txt",
                "text/plain",
                6,
                "0".repeat(64),
                () ->
                        new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)) {
                            @Override
                            public void close() {
                                closed.set(true);
                            }
                        });
    }

    @Test
    void oversizedTextPreviewRejectsBeforeAnyObjectRead() {
        var file =
                new FileCatalogService.VersionStream(
                        "large.txt",
                        "text/plain",
                        33L * 1024 * 1024,
                        "0".repeat(64),
                        () -> {
                            throw new AssertionError("Preview must not read the large object");
                        });
        assertThatThrownBy(() -> service.previewText(file, 512 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeReadsOnlyTheRequestedBytesAndClosesTheStream() throws Exception {
        var response = service.response(file(), "bytes=2-4");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 2-4/6");
        var bytes = DataBufferUtils.join(response.getBody()).block();
        try {
            assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("cde");
        } finally {
            DataBufferUtils.release(bytes);
        }
        assertThat(closed).isTrue();
        try (var permit = resources.acquire()) {
            assertThat(permit).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=6-", "bytes=4-1", "bytes=-0", "bytes=0-1,3-4", "not-a-range"})
    void rejectsInvalidOrUnsupportedRangesWithoutOpeningAnObject(String range) {
        var response = service.response(file(), range);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */6");
        assertThat(closed).isFalse();
    }

    @Test
    void suffixRangeAndEmptyDownloadHaveCorrectLengths() {
        var response = service.response(file(), "bytes=-2");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 4-5/6");
        var empty =
                service.response(
                        new FileCatalogService.VersionStream(
                                "empty.txt",
                                "text/plain",
                                0,
                                "0".repeat(64),
                                () -> {
                                    throw new AssertionError("Must not open empty object");
                                }),
                        null);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getHeaders().getContentLength()).isZero();
        assertThat(empty.getBody().collectList().block()).isEmpty();
    }
}
