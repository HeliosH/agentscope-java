/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.state;

/** Optional per-call storage namespace; does not change the externally visible session ID. */
public record AgentStateNamespace(String value) {
    public AgentStateNamespace {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid agent state namespace");
    }

    public String sessionKey(String sessionId) {
        return sessionId + "@" + value;
    }
}
