/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.saas.domain.memory.LegacyArchiveImportRepository.Job;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Bounded decoding of SessionTree JSONL. Rendered legacy tool text is never executable input. */
final class LegacyJsonlReader {
    record Line(
            long number,
            long start,
            long end,
            String hash,
            String sourceId,
            String parentId,
            String toolCallId,
            String type,
            boolean synthetic,
            Msg message) {}

    record Batch(List<Line> lines, long offset, String digestChain, boolean complete) {}

    private final ObjectMapper json;

    LegacyJsonlReader(ObjectMapper json) {
        this.json =
                json.copy()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Batch read(Path source, Job job, int maxLines, int maxLineBytes, int maxBatchBytes)
            throws IOException {
        var lines = new ArrayList<Line>();
        long offset = job.byteOffset();
        String chain = job.digestChain();
        try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
            channel.position(offset);
            try (var input = new BufferedInputStream(Channels.newInputStream(channel), 8192)) {
                while (lines.size() < maxLines) {
                    byte[] raw = line(input, maxLineBytes);
                    if (raw == null) break;
                    if (raw.length > maxBatchBytes - (offset - job.byteOffset())) break;
                    long next = Math.addExact(offset, raw.length);
                    Line parsed = parse(raw, job, job.lineNo() + lines.size() + 1, offset, next);
                    lines.add(parsed);
                    chain = hash(HexFormat.of().parseHex(chain + parsed.hash()));
                    offset = next;
                }
            }
        }
        return new Batch(List.copyOf(lines), offset, chain, offset == job.sourceBytes());
    }

    private static byte[] line(InputStream input, int maxBytes) throws IOException {
        var out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int value;
        while ((value = input.read()) != -1) {
            if (out.size() == maxBytes) throw invalid("LINE_LIMIT");
            out.write(value);
            if (value == '\n') break;
        }
        return out.size() == 0 ? null : out.toByteArray();
    }

    private Line parse(byte[] raw, Job job, long number, long start, long end) throws IOException {
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw))
                        .toString();
        if (start == 0 && text.startsWith("\uFEFF")) text = text.substring(1);
        String lineHash = hash(raw);
        if (text.isBlank())
            return new Line(number, start, end, lineHash, null, null, null, "blank", true, null);
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (IOException error) {
            throw invalid("JSON");
        }
        if (node == null || !node.isObject()) throw invalid("ENTRY");
        String type = field(node, "type", true);
        String id = field(node, "id", false);
        String parent = field(node, "parentId", false);
        String call = field(node, "toolCallId", false);
        if (!List.of("message", "compaction", "summary").contains(type)) throw invalid("TYPE");
        Msg message = null;
        if ("message".equals(type)) {
            MsgRole role;
            try {
                role = MsgRole.valueOf(field(node, "role", true).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw invalid("ROLE");
            }
            JsonNode content = node.get("content");
            if (content == null || !content.isTextual()) throw invalid("CONTENT");
            if (role == MsgRole.SYSTEM) type = "system";
            else {
                String identity = id == null ? "line:" + number : "id:" + id;
                String messageId =
                        "legacy-"
                                + hash(
                                        (job.sourceVersionId() + ":" + identity)
                                                .getBytes(StandardCharsets.UTF_8));
                String timestamp = field(node, "timestamp", false);
                if (timestamp != null) {
                    try {
                        Instant.parse(timestamp);
                    } catch (RuntimeException error) {
                        throw invalid("TIMESTAMP");
                    }
                }
                message =
                        Msg.builder()
                                .id(messageId)
                                .role(role)
                                .name("legacy_history")
                                .timestamp(timestamp == null ? Instant.EPOCH.toString() : timestamp)
                                .textContent(content.textValue())
                                .build();
            }
        }
        return new Line(number, start, end, lineHash, id, parent, call, type, id == null, message);
    }

    private static String field(JsonNode node, String key, boolean required) throws IOException {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) throw invalid("FIELD");
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 255)
            throw invalid("FIELD");
        return value.textValue();
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IOException invalid(String code) {
        return new IOException("LEGACY_IMPORT_" + code);
    }
}
