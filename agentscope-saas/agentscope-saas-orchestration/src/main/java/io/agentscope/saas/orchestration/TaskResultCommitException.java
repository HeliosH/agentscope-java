/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.orchestration;

/** Computation has returned; submission failure must not grant permission to rerun it. */
public final class TaskResultCommitException extends RuntimeException {
    public TaskResultCommitException(Throwable cause) {
        super("Completed task result could not be submitted", cause);
    }
}
