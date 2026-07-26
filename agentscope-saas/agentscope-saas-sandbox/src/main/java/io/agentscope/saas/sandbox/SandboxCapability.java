/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import java.util.Locale;

/** Provider-neutral capabilities that a sandbox deployment can guarantee. */
public enum SandboxCapability {
    SNAPSHOT,
    SUSPEND_RESUME,
    RESOURCE_LIMITS,
    NETWORK_POLICY,
    COMMAND_CANCEL,
    CONCURRENT_EXEC,
    CUSTOM_IMAGE,
    CUSTOM_TEMPLATE;

    public static SandboxCapability parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sandbox capability name must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown sandbox capability: " + value, error);
        }
    }
}
