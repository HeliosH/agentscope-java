/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

import java.util.Map;

/** Stable assistant-message origins distinguish reused provider call IDs from resumed intents. */
public record ToolInvocationOrigins(Map<String, String> messages) {
    public ToolInvocationOrigins {
        messages = Map.copyOf(messages);
    }

    public String origin(String callId, StepSnapshot step) {
        String origin = messages.get(callId);
        return origin != null
                ? origin
                : step != null && step.stepId() != null ? step.stepId() : "direct";
    }
}
