/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

/** Raised when a durable worker no longer owns the attempt that is executing a tool. */
public final class ToolLeaseLostException extends RuntimeException {
    public ToolLeaseLostException(String message) {
        super(message);
    }
}
