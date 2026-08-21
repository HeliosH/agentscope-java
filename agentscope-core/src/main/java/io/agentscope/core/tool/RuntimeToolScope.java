/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable call-scoped selection of the toolkit used for both model presentation and execution.
 *
 * <p>A singleton Agent may serve concurrent tenants. Runtime extensions must install a private
 * toolkit here instead of mutating {@link io.agentscope.core.agent.Agent#getToolkit()}.
 */
public final class RuntimeToolScope {

    private final Toolkit toolkit;
    private final String configurationHash;
    private final List<String> toolNames;
    private final Map<String, String> contributions;

    private RuntimeToolScope(
            Toolkit toolkit, String configurationHash, Map<String, String> contributions) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.configurationHash =
                configurationHash == null || configurationHash.isBlank()
                        ? hash(String.join("\n", toolkit.getToolNames().stream().sorted().toList()))
                        : configurationHash;
        this.toolNames = toolkit.getToolNames().stream().sorted().toList();
        this.contributions = immutableSortedMap(contributions);
    }

    public static RuntimeToolScope install(
            RuntimeContext context,
            Toolkit toolkit,
            String configurationHash,
            Map<String, String> contributions) {
        Objects.requireNonNull(context, "context");
        RuntimeToolScope scope = new RuntimeToolScope(toolkit, configurationHash, contributions);
        RuntimeToolScope existing = context.get(RuntimeToolScope.class);
        if (existing != null && !existing.configurationHash.equals(scope.configurationHash)) {
            throw new IllegalStateException(
                    "A different runtime tool scope is already installed for this call");
        }
        context.put(RuntimeToolScope.class, existing != null ? existing : scope);
        return existing != null ? existing : scope;
    }

    public static RuntimeToolScope current(RuntimeContext context) {
        return context == null ? null : context.get(RuntimeToolScope.class);
    }

    public static Toolkit resolve(RuntimeContext context, Toolkit fallback) {
        RuntimeToolScope scope = current(context);
        return scope != null ? scope.toolkit : fallback;
    }

    public Toolkit toolkit() {
        return toolkit;
    }

    public String configurationHash() {
        return configurationHash;
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public Map<String, String> contributions() {
        return contributions;
    }

    public static String hash(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Map<String, String> immutableSortedMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String key : keys) {
            if (key != null && values.get(key) != null) {
                sorted.put(key, values.get(key));
            }
        }
        return Collections.unmodifiableMap(sorted);
    }
}
