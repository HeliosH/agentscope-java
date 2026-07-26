/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.sandbox;

/**
 * Explicit per-call sandbox isolation key carried by a runtime context.
 *
 * <p>This override takes precedence over the agent's configured sharing scope. It is intended for
 * trusted orchestration code that must isolate a run or execution attempt without changing the
 * deployment-wide sandbox configuration.
 */
public record SandboxIsolationOverride(String key) {

    private static final int MAX_KEY_LENGTH = 512;

    public SandboxIsolationOverride {
        key = key == null ? null : key.trim();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Sandbox isolation override key is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Sandbox isolation override key exceeds " + MAX_KEY_LENGTH + " characters");
        }
    }
}
