/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

/** An unsealed compute result must not cause replay of potentially non-idempotent tools. */
public final class WorkspaceArchiveRejectedException extends RuntimeException {
    public WorkspaceArchiveRejectedException(Throwable cause) {
        super("Workspace output could not be safely handed over for archiving", cause);
    }
}
