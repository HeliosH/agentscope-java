/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.tool;

/** Signals that a prior side-effect outcome must be reconciled before execution can continue. */
public final class ToolOutcomeUnknownException extends RuntimeException {
    public ToolOutcomeUnknownException(String message) {
        super(message);
    }
}
