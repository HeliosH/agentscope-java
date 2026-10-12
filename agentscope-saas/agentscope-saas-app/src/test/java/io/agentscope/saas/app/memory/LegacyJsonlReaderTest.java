/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyJsonlReaderTest {
    @TempDir Path directory;
    private final LegacyJsonlReader reader = new LegacyJsonlReader(new ObjectMapper());

    private String entry(String text) {
        return "{\"type\":\"message\",\"role\":\"USER\",\"content\":\"" + text + "\"}";
    }

    @Test
    void byteBudgetDoesNotAdvanceOverNextLineAndResumesByByteCursor() throws Exception {
        String first = entry("one") + "\r\n", second = entry("\u4e8c") + "\n";
        byte[] bytes = (first + second).getBytes(StandardCharsets.UTF_8);
        Path source = directory.resolve("source.jsonl");
        Files.write(source, bytes);
        Job initial = job(bytes.length, 0, 0, "0".repeat(64));
        var batch =
                reader.read(
                        source, initial, 100, 1000, first.getBytes(StandardCharsets.UTF_8).length);
        assertThat(batch.lines()).hasSize(1);
        assertThat(batch.offset()).isEqualTo(first.getBytes(StandardCharsets.UTF_8).length);
        assertThat(batch.complete()).isFalse();
        var resumed =
                reader.read(
                        source,
                        withProgress(initial, batch.offset(), 1, batch.digestChain()),
                        100,
                        1000,
                        1000);
        assertThat(resumed.lines()).hasSize(1);
        assertThat(resumed.lines().get(0).message().getTextContent()).isEqualTo("\u4e8c");
        assertThat(resumed.lines().get(0).number()).isEqualTo(2);
        assertThat(resumed.complete()).isTrue();
        var all = reader.read(source, initial, 100, 1000, 1000);
        assertThat(resumed.digestChain()).isEqualTo(all.digestChain());
    }

    @Test
    void oversizeLineStopsBeforeDecodingOrAdvancing() throws Exception {
        Path source = directory.resolve("long.jsonl");
        String text = entry("a".repeat(1000)) + "\n";
        Files.writeString(source, text);
        assertThatThrownBy(
                        () ->
                                reader.read(
                                        source,
                                        job(text.length(), 0, 0, "0".repeat(64)),
                                        100,
                                        100,
                                        1000))
                .hasMessage("LEGACY_IMPORT_LINE_LIMIT");
    }

    @Test
    void invalidUtf8AndTrailingJsonFailRatherThanChangingEvidence() throws Exception {
        Path invalid = directory.resolve("utf8.jsonl");
        Files.write(invalid, new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
        assertThatThrownBy(() -> reader.read(invalid, job(3, 0, 0, "0".repeat(64)), 10, 1000, 1000))
                .isInstanceOf(java.io.IOException.class);
        String text = entry("first") + entry("second") + "\n";
        Files.writeString(invalid, text);
        assertThatThrownBy(
                        () ->
                                reader.read(
                                        invalid,
                                        job(text.length(), 0, 0, "0".repeat(64)),
                                        10,
                                        1000,
                                        1000))
                .hasMessage("LEGACY_IMPORT_JSON");
    }

    @Test
    void bomBlankLinesAndUnterminatedFinalLineHaveExactEvidenceOffsets() throws Exception {
        Path source = directory.resolve("bom.jsonl");
        byte[] bytes =
                ("\uFEFF" + entry("one") + "\n\n" + entry("final"))
                        .getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);
        var batch = reader.read(source, job(bytes.length, 0, 0, "0".repeat(64)), 10, 1000, 1000);
        assertThat(batch.lines()).hasSize(3);
        assertThat(batch.lines().get(1).type()).isEqualTo("blank");
        assertThat(batch.lines().get(2).message().getTextContent()).isEqualTo("final");
        assertThat(batch.offset()).isEqualTo(bytes.length);
        assertThat(batch.complete()).isTrue();
    }

    private Job job(long size, long offset, long lines, String chain) {
        var now = OffsetDateTime.now();
        return new Job(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                1,
                UUID.randomUUID(),
                "0".repeat(64),
                size,
                offset,
                lines,
                lines,
                0,
                chain,
                "READY",
                now,
                now);
    }

    private Job withProgress(Job job, long offset, long lines, String chain) {
        return new Job(
                job.id(),
                job.orgId(),
                job.userId(),
                job.agentId(),
                job.sessionId(),
                job.sessionGeneration(),
                job.formatVersion(),
                job.sourceVersionId(),
                job.sourceSha256(),
                job.sourceBytes(),
                offset,
                lines,
                lines,
                0,
                chain,
                "READY",
                job.createdAt(),
                job.updatedAt());
    }
}
