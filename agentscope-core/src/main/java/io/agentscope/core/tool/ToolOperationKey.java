/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

import java.util.Map;

/** Caller-scoped logical keys survive attempt replacement without colliding between agents. */
public final class ToolOperationKey {
    private ToolOperationKey() {}

    public static String of(String runOrSession, StepSnapshot.Identity identity, String callId) {
        return of(runOrSession, identity, "direct", callId);
    }

    public static String of(
            String runOrSession, StepSnapshot.Identity identity, String origin, String callId) {
        if (identity == null || identity.agentRunId() == null || identity.agentRunId().isBlank())
            return runOrSession + ":" + callId;
        return runOrSession
                + ":tool:"
                + StepSnapshot.fingerprint(
                        Map.of("caller", identity.agentRunId(), "origin", origin, "call", callId));
    }
}
