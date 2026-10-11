/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.config;

import io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy;
import io.agentscope.harness.agent.sandbox.WorkspaceTransferResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkspaceTransferConfiguration {
    @Bean
    public WorkspaceTransferResources workspaceTransferResources(SaasProperties properties) {
        var sandbox = properties.getSandbox();
        var policy =
                new WorkspaceTransferPolicy(
                        sandbox.getTransferMaxFiles(),
                        properties.getFileStore().getMaxFileBytes(),
                        sandbox.getTransferMaxBytes());
        if (Math.multiplyExact(
                        WorkspaceTransferResources.archiveLimit(policy),
                        Math.addExact((long) sandbox.getMaxConcurrentTransfers(), 1L))
                > sandbox.getMaxSpoolBytes())
            throw new IllegalArgumentException(
                    "Workspace spool budget must cover admitted captures plus one verification"
                            + " archive");
        if ("pg".equals(properties.getFileStore().getBackend())
                && properties.getFileStore().getMaxFileBytes() > 33554432)
            throw new IllegalArgumentException(
                    "PG development file backend supports at most 32 MiB per file; use MinIO for"
                            + " larger files");
        if (sandbox.isEnabled()
                && "opensandbox".equals(sandbox.getType())
                && WorkspaceTransferResources.archiveLimit(policy) > 536870912)
            throw new IllegalArgumentException(
                    "OpenSandbox archive transport supports at most 512 MiB");
        return new WorkspaceTransferResources(
                sandbox.getMaxConcurrentTransfers(),
                sandbox.getMaxSpoolBytes(),
                sandbox.getTransferBufferBytes());
    }
}
