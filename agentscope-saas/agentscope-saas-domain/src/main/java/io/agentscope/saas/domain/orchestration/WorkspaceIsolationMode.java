/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.domain.orchestration;

import java.util.Locale;

/** Provider-neutral workspace isolation policy persisted with an orchestration task. */
public enum WorkspaceIsolationMode {
    NONE,
    USER_SHARED_READ_ONLY,
    RUN_ISOLATED,
    ATTEMPT_ISOLATED,
    DEDICATED_SANDBOX;

    /** Reads canonical values and legacy values written before this enum was introduced. */
    public static WorkspaceIsolationMode fromStorage(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ISOLATED_ATTEMPT".equals(normalized)) {
            return ATTEMPT_ISOLATED;
        }
        if ("ISOLATED_RUN".equals(normalized)) {
            return RUN_ISOLATED;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported workspace isolation mode: " + value, e);
        }
    }
}
