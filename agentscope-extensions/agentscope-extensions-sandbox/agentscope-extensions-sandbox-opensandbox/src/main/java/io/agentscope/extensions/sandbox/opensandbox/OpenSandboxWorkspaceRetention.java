/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceRetentionBackend;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Uses native file IO on the original resource, without resume/create or workspace hydration. */
public final class OpenSandboxWorkspaceRetention implements WorkspaceRetentionBackend {
    private final OpenSandboxPlatformHttp http;

    public OpenSandboxWorkspaceRetention(OpenSandboxClientOptions options) {
        http = new OpenSandboxPlatformHttp(options);
    }

    public String providerId() {
        return "opensandbox";
    }

    public void seal(Sandbox sandbox, UUID sourceId, Path capture, OffsetDateTime deadline)
            throws Exception {
        if (!(sandbox instanceof OpenSandbox)
                || !(sandbox.getState() instanceof OpenSandboxState state)
                || !state.isSandboxOwned())
            throw new IllegalStateException("Retention requires an owned OpenSandbox resource");
        http.renewExpiration(state.getSandboxId(), deadline);
        http.uploadFile(state, path(sourceId), capture);
    }

    public void retrieve(String resourceId, UUID sourceId, Path target, long maxBytes)
            throws Exception {
        // A missing resource is an error, not permission to create a fresh empty sandbox.
        UUID.fromString(resourceId);
        var state = new OpenSandboxState();
        state.setSandboxId(resourceId);
        http.downloadFile(state, path(sourceId), target, maxBytes);
    }

    public void terminate(String resourceId) throws Exception {
        UUID.fromString(resourceId);
        http.deleteSandbox(resourceId);
    }

    private static String path(UUID sourceId) {
        return "/tmp/agentscope-retained-" + sourceId + ".tar";
    }
}
