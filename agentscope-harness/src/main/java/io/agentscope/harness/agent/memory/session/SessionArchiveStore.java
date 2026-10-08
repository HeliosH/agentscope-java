/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.memory.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Durable ordered transcript port. File-only Harness deployments may leave this unconfigured. */
public interface SessionArchiveStore {
    String PROJECTION_SOURCE_HASH = "harness.archive.sourceHash";

    record Receipt(long lastSeq, int appended, String reference) {}

    record Entry(long seq, Msg message) {}

    record Hit(String agentLabel, String sessionKey, long seq, String role, String preview) {}

    record Session(String agentLabel, String sessionKey, long lastSeq) {}

    Receipt append(
            RuntimeContext context, String agentLabel, String sessionKey, List<Msg> messages);

    List<Entry> readWindow(
            RuntimeContext context,
            String agentLabel,
            String sessionKey,
            Long afterSeq,
            Long beforeSeq,
            int limit);

    List<Hit> search(RuntimeContext context, String agentLabel, String query, int limit);

    List<Session> list(RuntimeContext context, String agentLabel, int limit);

    String reference(RuntimeContext context, String agentLabel, String sessionKey);

    static boolean sourceMessage(Msg message) {
        if (message == null || message.getRole() == null || message.getRole() == MsgRole.SYSTEM)
            return false;
        if ("__compaction_summary__".equals(message.getName())
                || "compaction_summary".equals(message.getName())
                || "long_term_memory".equals(message.getName())) return false;
        return true;
    }

    /** Timestamp, routing and working-state metadata are not part of source message identity. */
    static String payload(Msg message) {
        Msg source =
                Msg.builder()
                        .id(message.getId())
                        .role(message.getRole())
                        .name(message.getName())
                        .content(message.getContent())
                        .timestamp(message.getTimestamp())
                        .metadata(Map.of())
                        .build();
        return JsonUtils.getJsonCodec().toJson(source);
    }

    static String digest(Msg message) {
        // Approval and execution update tool-call state without changing the model's request.
        Msg identity =
                message.withContent(
                        message.getContent().stream()
                                .map(
                                        block ->
                                                block instanceof ToolUseBlock tool
                                                        ? tool.withState(ToolCallState.PENDING)
                                                        : block)
                                .toList());
        Object value = JsonUtils.getJsonCodec().fromJson(payload(identity), Object.class);
        if (value instanceof Map<?, ?> map) map.remove("timestamp");
        String canonical = JsonUtils.getJsonCodec().toJson(canonical(value));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(SessionArchiveStore::canonical).toList();
        if (value instanceof Number number)
            return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    /** A bounded working projection must refer to a previously committed full source. */
    static Msg projection(Msg original, Msg projected) {
        if (!java.util.Objects.equals(original.getId(), projected.getId())
                || original.getRole() != projected.getRole()
                || !java.util.Objects.equals(original.getName(), projected.getName()))
            throw new IllegalArgumentException("Working projection must preserve message identity");
        Object prior = original.getMetadata().get(PROJECTION_SOURCE_HASH);
        projected
                .getMetadata()
                .put(PROJECTION_SOURCE_HASH, prior != null ? prior : digest(original));
        return projected;
    }

    final class ArchiveCommitException extends RuntimeException {
        public ArchiveCommitException(Throwable cause) {
            super("Runtime archive commit failed", cause);
        }
    }
}
