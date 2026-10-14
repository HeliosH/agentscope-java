/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

import io.agentscope.core.util.JsonUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, immutable executable contract, independent of hosting storage and tenant types. */
public record ToolContractSnapshot(
        int formatVersion, String descriptorJson, String hash, boolean recoverable) {
    private static final int MAX_DESCRIPTOR_BYTES = 262144;
    private static final ClassValue<String> CLASS_VERSIONS =
            new ClassValue<>() {
                @Override
                protected String computeValue(Class<?> type) {
                    var versions = new LinkedHashMap<String, String>();
                    for (Class<?> current = type;
                            current != null && !current.getName().startsWith("java.");
                            current = current.getSuperclass()) {
                        try (InputStream stream =
                                current.getResourceAsStream(
                                        "/" + current.getName().replace('.', '/') + ".class")) {
                            if (stream == null) return null;
                            var digest = MessageDigest.getInstance("SHA-256");
                            byte[] buffer = new byte[8192];
                            int total = 0;
                            for (int count; (count = stream.read(buffer)) != -1; ) {
                                total += count;
                                if (total > 16777216) return null;
                                digest.update(buffer, 0, count);
                            }
                            versions.put(
                                    current.getName(), HexFormat.of().formatHex(digest.digest()));
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    return versions.isEmpty() ? null : StepSnapshot.fingerprint(versions);
                }
            };

    public ToolContractSnapshot {
        if (formatVersion != 1
                || descriptorJson == null
                || descriptorJson.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTOR_BYTES)
            throw new IllegalArgumentException("Unsupported or oversized tool contract");
        Object descriptor = JsonUtils.getJsonCodec().fromJson(descriptorJson, Object.class);
        validateJson(descriptor, 0, new int[2]);
        if (!(descriptor instanceof Map<?, ?> values)
                || hash == null
                || !hash.equals(StepSnapshot.fingerprint(descriptor)))
            throw new IllegalArgumentException(
                    "Tool contract digest does not match its descriptor");
        Object version = values.get("implementationVersion");
        if (recoverable != (version instanceof String text && !text.isBlank()))
            throw new IllegalArgumentException(
                    "Tool recovery requires an explicit implementation identity");
    }

    /** Local bytecode identity; mutable remote/configured behavior must provide its own version. */
    public static String implementationVersion(Class<?> type) {
        return CLASS_VERSIONS.get(type);
    }

    public static ToolContractSnapshot capture(
            AgentTool tool, Map<String, Object> presetParameters) {
        var descriptor = new LinkedHashMap<String, Object>();
        String version = tool.getContractVersion();
        descriptor.put("name", tool.getName());
        descriptor.put("implementationType", tool.getClass().getName());
        descriptor.put("implementationVersion", version);
        descriptor.put("description", tool.getDescription());
        descriptor.put("inputSchema", tool.getParameters());
        descriptor.put("outputSchema", tool.getOutputSchema());
        descriptor.put("strict", tool.getStrict());
        descriptor.put(
                "retrySafety",
                tool.getRetrySafety() == null
                        ? ToolRetrySafety.NEVER.name()
                        : tool.getRetrySafety().name());
        Map<String, Object> presets = presetParameters == null ? Map.of() : presetParameters;
        validateJson(presets, 0, new int[2]);
        descriptor.put("presetHash", StepSnapshot.fingerprint(presets));
        descriptor.put("attributes", tool.getContractAttributes());
        if (tool instanceof ToolBase base) {
            descriptor.put("readOnly", base.isReadOnly());
            descriptor.put("concurrencySafe", base.isConcurrencySafe());
            descriptor.put("stateInjected", base.isStateInjected());
            descriptor.put("externalTool", base.isExternalTool());
            descriptor.put("mcpName", base.getMcpName());
        }
        validateJson(descriptor, 0, new int[2]);
        return new ToolContractSnapshot(
                1,
                JsonUtils.getJsonCodec().toJson(descriptor),
                StepSnapshot.fingerprint(descriptor),
                version != null && !version.isBlank());
    }

    public String toolName() {
        return attribute("name");
    }

    public String retrySafety() {
        return attribute("retrySafety");
    }

    private String attribute(String name) {
        Object descriptor = JsonUtils.getJsonCodec().fromJson(descriptorJson, Object.class);
        Object value = ((Map<?, ?>) descriptor).get(name);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Invalid tool contract " + name);
        return text;
    }

    private static void validateJson(Object value, int depth, int[] size) {
        if (depth > 64 || ++size[0] > 65536)
            throw new IllegalArgumentException("Tool contract structure exceeds its limit");
        if (value == null) size[1] += 4;
        else if (value instanceof String text)
            size[1] += (int) Math.min(MAX_DESCRIPTOR_BYTES + 1L, text.length() * 6L + 2L);
        else if (value instanceof Boolean || value instanceof Number)
            size[1] += value.toString().length() + 1;
        else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String))
                    throw new IllegalArgumentException("Tool contract requires JSON object keys");
                validateJson(entry.getKey(), depth + 1, size);
                validateJson(entry.getValue(), depth + 1, size);
            }
        } else if (value instanceof java.util.Collection<?> values) {
            for (Object item : values) validateJson(item, depth + 1, size);
        } else
            throw new IllegalArgumentException(
                    "Tool contract attributes must be JSON values, not live objects");
        if (size[1] > MAX_DESCRIPTOR_BYTES)
            throw new IllegalArgumentException("Tool contract exceeds its byte limit");
    }
}
