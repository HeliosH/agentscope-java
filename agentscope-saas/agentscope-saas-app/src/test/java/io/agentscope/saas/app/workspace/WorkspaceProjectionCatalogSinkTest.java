/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.saas.app.workspace.FileCatalogService.FileRecord;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceProjectionCatalogSinkTest {

    @Test
    void recordsTheCatalogReceiptForADurableCall() {
        FileCatalogService catalog = mock(FileCatalogService.class);
        FileRecord record =
                new FileRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "/result.txt",
                        1,
                        "object-key",
                        "minio",
                        6,
                        "sha256");
        when(catalog.recordWorkspaceFile(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(record));
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(true);
        RuntimeContext context = context(checkpoint);

        new WorkspaceProjectionCatalogSink(catalog)
                .onProjectedFile(context, "/result.txt", "result".getBytes());
        checkpoint.projectionSucceeded(1);

        checkpoint.verifyReady();
        assertThat(checkpoint.files()).containsExactly(record);
    }

    @Test
    void convertsBestEffortCatalogErrorsIntoDurableCheckpointFailure() {
        FileCatalogService catalog = mock(FileCatalogService.class);
        when(catalog.recordWorkspaceFile(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("object store unavailable"));
        WorkspaceCheckpointContext checkpoint = new WorkspaceCheckpointContext(true);
        RuntimeContext context = context(checkpoint);

        new WorkspaceProjectionCatalogSink(catalog)
                .onProjectedFile(context, "/result.txt", "result".getBytes());
        checkpoint.projectionSucceeded(1);

        assertThatThrownBy(checkpoint::verifyReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("object store unavailable");
    }

    private static RuntimeContext context(WorkspaceCheckpointContext checkpoint) {
        TenantContext tenant =
                new TenantContext(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "member",
                        "standard",
                        2,
                        100_000);
        return RuntimeContext.builder()
                .sessionId(UUID.randomUUID().toString())
                .put(WorkspaceProjectionCatalogSink.ATTR_AGENT_ID, UUID.randomUUID().toString())
                .put(TenantContext.class, tenant)
                .put(TenantContext.ATTR_KEY, tenant)
                .put(WorkspaceCheckpointContext.class, checkpoint)
                .build();
    }
}
