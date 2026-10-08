/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.orchestration;

/** The session was reset or deleted; the previous execution must not be resumed or published. */
public class SessionExecutionRevokedException extends IllegalStateException {
    public SessionExecutionRevokedException() {
        super("SESSION_EXECUTION_REVOKED");
    }
}
