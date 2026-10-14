/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.storage.FileObjectStore;
import java.io.InputStream;
import java.util.UUID;

/** An authorized immutable descriptor may be opened on a different scheduler thread. */
final class WorkspaceObjectReads {
    private WorkspaceObjectReads() {}

    static InputStream open(FileObjectStore store, UUID org, String key, long size)
            throws Exception {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(org.toString());
        try {
            return store.openRead(org, key, size);
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }
}
