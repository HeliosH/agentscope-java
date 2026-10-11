/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import java.net.URI;
import java.util.UUID;

/** Opaque catalog checkpoint reference; never a provider URL or an object-store key. */
public final class WorkspaceCheckpointReference {
    private WorkspaceCheckpointReference() {}

    public static String pinned(UUID runId, UUID attemptId, UUID manifestId) {
        return "workspace-catalog://runs/"
                + runId
                + "/attempts/"
                + attemptId
                + "/manifests/"
                + manifestId;
    }

    public static UUID manifest(String value, UUID runId, UUID attemptId) {
        try {
            URI uri = URI.create(value);
            if (!"workspace-catalog".equals(uri.getScheme())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            String prefix = "/" + runId + "/attempts/" + attemptId;
            if ("attempts".equals(uri.getRawAuthority())
                    && ("/" + attemptId).equals(uri.getRawPath())) return null;
            if (!"runs".equals(uri.getRawAuthority())) throw new IllegalArgumentException();
            if (prefix.equals(uri.getRawPath())) return null;
            String expected = prefix + "/manifests/";
            if (!uri.getRawPath().startsWith(expected)) throw new IllegalArgumentException();
            UUID id = UUID.fromString(uri.getRawPath().substring(expected.length()));
            if (!pinned(runId, attemptId, id).equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WORKSPACE_CHECKPOINT_INVALID_REFERENCE", e);
        }
    }
}
